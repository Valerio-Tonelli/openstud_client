package com.lithium.leona.openstud.fragments;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.snackbar.Snackbar;
import com.lithium.leona.openstud.R;
import com.lithium.leona.openstud.activities.ExamsActivity;
import com.lithium.leona.openstud.adapters.ActiveReservationsAdapter;
import com.lithium.leona.openstud.data.InfoManager;
import com.lithium.leona.openstud.helpers.ClientHelper;

import org.threeten.bp.Duration;
import org.threeten.bp.LocalDateTime;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.LinkedList;
import java.util.List;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import lithium.openstud.driver.core.models.ExamReservation;
import lithium.openstud.driver.exceptions.OpenstudConnectionException;
import lithium.openstud.driver.exceptions.OpenstudInvalidCredentialsException;
import lithium.openstud.driver.exceptions.OpenstudInvalidResponseException;

public class ReservationsFragment extends BaseDataFragment {

    @BindView(R.id.swipe_refresh)
    SwipeRefreshLayout swipeRefreshLayout;
    @BindView(R.id.recyclerView)
    RecyclerView rv;
    @BindView(R.id.empty_layout)
    LinearLayout emptyView;
    @BindView(R.id.empty_button_reload)
    Button emptyButton;
    @BindView(R.id.empty_text)
    TextView emptyText;
    private List<ExamReservation> reservations;
    private ActiveReservationsAdapter adapter;
    private LocalDateTime lastUpdate;
    private boolean firstStart = true;
    private ReservationsHandler h = new ReservationsHandler(this);

    @OnClick(R.id.empty_button_reload)
    public void OnClick(View v) {
        refreshReservations();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.base_swipe_fragment, null);
        Activity activity = getActivity();
        if (!initData() || activity == null) return v;
        ButterKnife.bind(this, v);
        reservations = new LinkedList<>();
        emptyText.setText(getResources().getString(R.string.no_reservations_found));
        rv.setHasFixedSize(true);
        LinearLayoutManager llm = new LinearLayoutManager(activity);
        rv.setLayoutManager(llm);
        adapter = new ActiveReservationsAdapter(activity, reservations, new ActiveReservationsAdapter.ReservationAdapterListener() {
            @Override
            public void deleteReservationOnClick(final ExamReservation res) {
                if (!ClientHelper.canDeleteReservation(res)) {
                    h.sendEmptyMessage(ClientHelper.Status.CLOSED_RESERVATION.getValue());
                    return;
                }
                ClientHelper.createConfirmDeleteReservationDialog(activity, res, () -> deleteReservation(res));
            }

            @Override
            public void downloadReservationOnClick(final ExamReservation res) {
                new Thread(() -> getFile(activity, res)).start();
            }

            @Override
            public void addCalendarOnClick(ExamReservation res) {
                ClientHelper.addReservationToCalendar(activity, res);
            }
        }, rv);
        rv.setAdapter(adapter);
        swipeRefreshLayout.measure(1, 1);
        swipeRefreshLayout.setColorSchemeResources(R.color.refresh1, R.color.refresh2, R.color.refresh3);
        swipeRefreshLayout.setOnRefreshListener(this::refreshReservations);
        swipeRefreshLayout.setEnabled(false);
        new Thread(() -> {
            List<ExamReservation> reservations_cached = InfoManager.getActiveReservationsCached(activity, os);
            if (reservations_cached != null && !reservations_cached.isEmpty()) {
                reservations.addAll(reservations_cached);
            } else swapViews(reservations_cached);
            activity.runOnUiThread(() -> {
                adapter.notifyDataSetChanged();
                swipeRefreshLayout.setEnabled(true);
            });
            if (savedInstanceState == null) {
                try {
                    Thread.sleep(500);
                    refreshReservations();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }).start();
        return v;
    }

    private void getFile(Activity activity, ExamReservation res) {
        boolean check = false;
        String directory = activity.getExternalFilesDir("/OpenStud/pdf/reservation").getPath();
        File dirs = new File(directory);
        dirs.mkdirs();
        File pdfFile = new File(directory + res.getSessionID() + "_" + res.getExamSubject() + "_" + res.getReservationNumber() + ".pdf");
        try {
            if (pdfFile.exists()) {
                ClientHelper.openActionViewPDF(activity, pdfFile);
                return;
            }
            pdfFile.createNewFile();
            FileOutputStream fos = new FileOutputStream(pdfFile);
            byte[] content = os.getPdf(res);
            fos.write(content);
            fos.close();
            check = true;
        } catch (OpenstudConnectionException | OpenstudInvalidResponseException e) {
            h.sendEmptyMessage(ClientHelper.Status.FAILED_GET.getValue());
            e.printStackTrace();
        } catch (OpenstudInvalidCredentialsException e) {
            h.sendEmptyMessage(ClientHelper.Status.INVALID_CREDENTIALS.getValue());
            e.printStackTrace();
        } catch (IOException e) {
            h.sendEmptyMessage(ClientHelper.Status.FAILED_GET_IO.getValue());
            e.printStackTrace();
        }
        if (!check) {
            pdfFile.delete();
            return;
        }
        ClientHelper.openActionViewPDF(activity, pdfFile);
    }


    public void onResume() {
        super.onResume();
        LocalDateTime time = getTimer();
        Activity activity = getActivity();
        if (firstStart) firstStart = false;
        else if (activity != null && (time == null || Duration.between(time, LocalDateTime.now()).toMinutes() > 30))
            refreshReservations();
        else if (activity != null && InfoManager.getReservationUpdateFlag(activity)) {
            refreshReservations();
            InfoManager.setReservationUpdateFlag(activity, false);
        }
    }

    private void refreshReservations() {
        final Activity activity = getActivity();
        if (activity == null || os == null) return;
        setRefreshing(true);
        setButtonReloadStatus(false);
        new Thread(() -> {
            List<ExamReservation> update = null;
            try {
                update = InfoManager.getActiveReservations(activity.getApplication(), os);
                if (update == null)
                    h.sendEmptyMessage(ClientHelper.Status.UNEXPECTED_VALUE.getValue());
                else h.sendEmptyMessage(ClientHelper.Status.OK.getValue());

            } catch (OpenstudConnectionException e) {
                h.sendEmptyMessage(ClientHelper.Status.CONNECTION_ERROR.getValue());
                e.printStackTrace();
            } catch (OpenstudInvalidResponseException e) {
                if (e.isMaintenance())
                    h.sendEmptyMessage(ClientHelper.Status.MAINTENANCE.getValue());
                h.sendEmptyMessage(ClientHelper.Status.INVALID_RESPONSE.getValue());
                e.printStackTrace();
            } catch (OpenstudInvalidCredentialsException e) {
                if (e.isPasswordExpired())
                    h.sendEmptyMessage(ClientHelper.Status.EXPIRED_CREDENTIALS.getValue());
                else h.sendEmptyMessage(ClientHelper.Status.INVALID_CREDENTIALS.getValue());
                e.printStackTrace();
            }

            if (update == null) {
                setRefreshing(false);
                setButtonReloadStatus(true);
                swapViews(reservations);
                return;
            }
            updateTimer();
            refreshDataSet(update);
        }).start();
    }

    private synchronized void refreshDataSet(List<ExamReservation> update) {
        boolean flag = false;
        if (update != null && !reservations.equals(update)) {
            flag = true;
            reservations.clear();
            reservations.addAll(update);
        }
        final boolean finalFlag = flag;
        Activity activity = getActivity();
        if (activity == null) return;
        activity.runOnUiThread(() -> {
            if (finalFlag) {
                adapter.notifyDataSetChanged();
                ClientHelper.updateGradesWidget(activity, false);
                ClientHelper.updateExamWidget(activity, false);
            }
            swapViews(reservations);
            swipeRefreshLayout.setRefreshing(false);
            emptyButton.setEnabled(true);
        });
    }

    private void setRefreshing(final boolean bool) {
        Activity activity = getActivity();
        if (activity == null) return;
        activity.runOnUiThread(() -> swipeRefreshLayout.setRefreshing(bool));
    }

    private void setButtonReloadStatus(final boolean bool) {
        Activity activity = getActivity();
        if (activity == null) return;
        activity.runOnUiThread(() -> emptyButton.setEnabled(bool));
    }

    private void swapViews(final List<ExamReservation> reservations) {
        Activity activity = getActivity();
        if (activity == null) return;
        activity.runOnUiThread(() -> {
            if (reservations == null || reservations.isEmpty()) {
                emptyView.setVisibility(View.VISIBLE);
                rv.setVisibility(View.GONE);
            } else {
                emptyView.setVisibility(View.GONE);
                rv.setVisibility(View.VISIBLE);
            }
        });
    }

    private synchronized void updateTimer() {
        lastUpdate = LocalDateTime.now();
    }

    private synchronized LocalDateTime getTimer() {
        return lastUpdate;
    }

    private void deleteReservation(ExamReservation res) {
        try {
            int ret = os.deleteReservation(res);
            if (ret != -1) {
                synchronized (this) {
                    refreshReservations();
                }
                h.sendEmptyMessage(ClientHelper.Status.OK_DELETE.getValue());
            } else h.sendEmptyMessage(ClientHelper.Status.FAILED_DELETE.getValue());
        } catch (OpenstudInvalidResponseException | OpenstudConnectionException e) {
            h.sendEmptyMessage(ClientHelper.Status.FAILED_DELETE.getValue());
        } catch (OpenstudInvalidCredentialsException e) {
            h.sendEmptyMessage(ClientHelper.Status.INVALID_CREDENTIALS.getValue());
        }
    }

    private static class ReservationsHandler extends Handler {
        private final WeakReference<ReservationsFragment> frag;

        private ReservationsHandler(ReservationsFragment frag) {
            this.frag = new WeakReference<>(frag);
        }

        @Override
        public void handleMessage(Message msg) {
            final ReservationsFragment reservationsFrag = frag.get();
            if (reservationsFrag == null) return;
            ExamsActivity activity = (ExamsActivity) reservationsFrag.getActivity();
            if (activity != null) {
                View.OnClickListener listener = v -> reservationsFrag.refreshReservations();
                if (msg.what == ClientHelper.Status.CONNECTION_ERROR.getValue()) {
                    activity.createRetrySnackBar(R.string.connection_error, Snackbar.LENGTH_LONG, listener);
                } else if (msg.what == ClientHelper.Status.INVALID_RESPONSE.getValue()) {
                    activity.createRetrySnackBar(R.string.invalid_response_error, Snackbar.LENGTH_LONG, listener);
                } else if (msg.what == ClientHelper.Status.MAINTENANCE.getValue()) {
                    activity.createRetrySnackBar(R.string.infostud_maintenance, Snackbar.LENGTH_LONG, listener);
                } else if (msg.what == ClientHelper.Status.USER_NOT_ENABLED.getValue()) {
                    activity.createTextSnackBar(R.string.user_not_enabled_error, Snackbar.LENGTH_LONG);
                } else if (msg.what == (ClientHelper.Status.INVALID_CREDENTIALS).getValue() || msg.what == ClientHelper.Status.EXPIRED_CREDENTIALS.getValue()) {
                    ClientHelper.rebirthApp(activity, msg.what);
                } else if (msg.what == (ClientHelper.Status.FAILED_DELETE).getValue()) {
                    activity.createTextSnackBar(R.string.failed_delete, Snackbar.LENGTH_LONG);
                } else if (msg.what == (ClientHelper.Status.OK_DELETE).getValue()) {
                    activity.createTextSnackBar(R.string.ok_delete, Snackbar.LENGTH_LONG);
                } else if (msg.what == ClientHelper.Status.FAILED_GET_IO.getValue()) {
                    activity.createTextSnackBar(R.string.failed_get_io, Snackbar.LENGTH_LONG);
                } else if (msg.what == ClientHelper.Status.FAILED_GET.getValue()) {
                    activity.createTextSnackBar(R.string.failed_get_network, Snackbar.LENGTH_LONG);
                } else if (msg.what == ClientHelper.Status.CLOSED_RESERVATION.getValue()) {
                    activity.createTextSnackBar(R.string.closed_reservation, Snackbar.LENGTH_LONG);
                } else if (msg.what == ClientHelper.Status.UNEXPECTED_VALUE.getValue()) {
                    activity.createTextSnackBar(R.string.invalid_response_error, Snackbar.LENGTH_LONG);
                }
            }
        }
    }
}
