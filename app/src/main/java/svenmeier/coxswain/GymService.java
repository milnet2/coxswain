/*
 * Copyright 2015 Sven Meier
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package svenmeier.coxswain;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.IBinder;

import propoid.util.content.Preference;
import svenmeier.coxswain.gym.Program;
import svenmeier.coxswain.motivator.DefaultMotivator;
import svenmeier.coxswain.motivator.Motivator;
import svenmeier.coxswain.rower.Rower;
import svenmeier.coxswain.rower.mock.MockRower;
import svenmeier.coxswain.rower.water.WaterRower;

public class GymService extends Service {

    private Gym gym;

    private Handler handler = new Handler();

    private Preference<Boolean> openEnd;

    private Rowing rowing;

    private Foreground foreground;

    public GymService() {
    }

    @Override
    public void onCreate() {
        gym = Gym.instance(this);

        openEnd = Preference.getBoolean(this, R.string.preference_open_end);

        foreground = new Foreground();
    }

    @Override
    public void onDestroy() {
        if (this.rowing != null) {
            endRowing();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

        if (this.rowing != null) {
            endRowing();
        }

        startRowing(device);

        return START_NOT_STICKY;
    }

    private void startRowing(UsbDevice device) {

        Rower rower;
        if (device == null) {
            rower = new MockRower();
        } else {
            rower = new WaterRower(this, device);
        }

        rowing = new Rowing(rower);
        new Thread(rowing).start();
    }

    @Override
    public IBinder onBind(Intent intent) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    private void endRowing() {
        this.rowing = null;
    }

    /**
     * Current rowing on rower.
     */
    private class Rowing implements Runnable {

        private final Rower rower;

        private Heart heart;

        private final Motivator motivator;

        private Program program;

        public Rowing(Rower rower) {
            this.rower = rower;

            this.heart = Heart.create(GymService.this, rower);

            this.motivator = new DefaultMotivator(GymService.this);
        }

        public void run() {
            if (rower.open()) {
                while (true) {
                    if (GymService.this.rowing != this) {
                        break;
                    }

                    if (gym.program != program) {
                        // program changed
                        program = gym.program;

                        rower.reset();
                    }

                    if (rower.row() == false) {
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                gym.deselect();
                            }
                        });
                        break;
                    }

                    heart.pulse();

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (GymService.this.rowing != Rowing.this) {
                                // no longer current
                                return;
                            }

                            if (gym.program ==  null) {
                                foreground.connected(String.format(getString(R.string.gym_notification_connected), rower.getName()));
                                return;
                            } else if (gym.program != program) {
                                // program changed
                                return;
                            }

                            String text = program.name.get();
                            float completion = 0;
                            if (gym.progress != null) {
                                text += " - " +  gym.progress.describe();
                                completion = gym.progress.completion();
                            }
                            foreground.workout(text, completion);

                            Event event = gym.onMeasured(rower);
                            motivator.onEvent(event);

                            if (event == Event.PROGRAM_FINISHED && openEnd.get() == false) {
                                gym.deselect();
                            }
                        }
                    });
                }

                rower.close();
            }

            handler.post(new Runnable() {
                @Override
                public void run() {
                    motivator.destroy();

                    heart.destroy();

                    foreground.stop();
                }
            });
        }

    }

    private class Foreground {

        private Preference<Boolean> headsup;

        private boolean started;

        private String text;

        private int progress = -1;

        private long headsupSince;

        private Notification.Builder builder;

        public Foreground() {
            headsup = Preference.getBoolean(GymService.this, R.string.preference_integration_headsup);

            builder = new Notification.Builder(GymService.this)
                    .setSmallIcon(R.drawable.notification)
                    .setContentTitle(getString(R.string.app_name))
                    .setOngoing(true);
        }

        public void connected(String text) {
            GymService service = GymService.this;

            if (text.equals(this.text)) {
                return;
            }

            builder.setContentIntent(PendingIntent.getActivity(service, 1, new Intent(service, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT));
            builder.setDefaults(Notification.DEFAULT_VIBRATE);
            builder.setContentText(text);
            builder.setProgress(0, 0, false);
            builder.setPriority(Notification.PRIORITY_DEFAULT);

            start(builder.build());

            this.text = text;
            this.progress = -1;
        }

        public void workout(String text, float completion) {
            GymService service = GymService.this;

            int progress = (int)(completion * 100);

            if (text.equals(this.text) && progress == this.progress) {
                return;
            }

            builder.setContentIntent(PendingIntent.getActivity(service, 1, new Intent(service, WorkoutActivity.class), PendingIntent.FLAG_UPDATE_CURRENT));

            if (text.equals(this.text)) {
                // no vibration, but needs empty array to keep headsup
                builder.setDefaults(0);
                builder.setVibrate(new long[0]);
            } else {
                builder.setDefaults(Notification.DEFAULT_VIBRATE);
            }

            builder.setContentText(text);
            builder.setProgress(100, progress, false);

            if (headsUp()) {
                builder.setPriority(Notification.PRIORITY_HIGH);
            } else {
                builder.setPriority(Notification.PRIORITY_DEFAULT);
            }

            Notification notification = builder.build();

            start(notification);

            this.text = text;
            this.progress = progress;
        }

        private boolean headsUp() {
            if (headsup.get()) {
                if (gym.hasListener(Object.class)) {
                    headsupSince = 0;
                } else {
                    if (headsupSince == 0) {
                        headsupSince = System.currentTimeMillis();
                    } else {
                        if (System.currentTimeMillis() - headsupSince > 2000) {
                            return true;
                        }
                    }
                }
            }

            return false;
        }

        private void start(Notification notification) {
            if (started) {
                NotificationManager notificationManager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
                notificationManager.notify(1, notification);
            } else {
                startForeground(1, notification);
            }
            started = true;
        }

        public void stop() {
            text = null;
            progress = -1;

            stopForeground(true);

            started = false;
        }
    }

    public static void start(Context context, UsbDevice device) {
        Intent serviceIntent = new Intent(context, GymService.class);

        if (device != null) {
            serviceIntent.putExtra(UsbManager.EXTRA_DEVICE, device);
        }

        context.startService(serviceIntent);
    }
}
