package svenmeier.coxswain.heart.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.support.annotation.RequiresApi;
import android.util.Log;

import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;

import svenmeier.coxswain.Coxswain;
import svenmeier.coxswain.heart.bluetooth.constants.BluetoothHeartServices;
import svenmeier.coxswain.heart.bluetooth.device.BluetoothHeartDevice;
import svenmeier.coxswain.util.Destroyable;

import static android.bluetooth.le.ScanSettings.CALLBACK_TYPE_ALL_MATCHES;
import static android.bluetooth.le.ScanSettings.CALLBACK_TYPE_FIRST_MATCH;
import static android.bluetooth.le.ScanSettings.CALLBACK_TYPE_MATCH_LOST;
import static android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY;

@RequiresApi(api = Build.VERSION_CODES.N)
public class BluetoothLeHeartScanner {
    final Context context;

    private final ScanSettings DISCOVERY_SCAN = new ScanSettings.Builder()
            .setScanMode(SCAN_MODE_LOW_LATENCY)
            .build();

    private final List<ScanFilter> DEVICES_FILTER = ImmutableList.of(
        new ScanFilter.Builder()
            .setServiceUuid(BluetoothHeartServices.HEART_RATE.getParcelUuid())
            .build());

    public BluetoothLeHeartScanner(Context context) {
        this.context = context;
    }

    /**
     *  Scans for bluetooth devices invoking the handler on discovery.
     *  In order to stop the scan call destroy on the returned object. Else the scan will
     *  continue indefinitely consuming high energy.
     */
    public Destroyable scan(final BluetoothHeartDiscoveryListener handler) {
        final OnDevice listener = new OnDevice(handler);
Log.i(Coxswain.TAG, "Scan 1");
         final Optional<BluetoothAdapter> adapter = getBluetoothAdapter();
        Log.i(Coxswain.TAG, "Scan 2");
         if (adapter.isPresent()) {
             final BluetoothLeScanner scanner = adapter.get().getBluetoothLeScanner();
             Log.i(Coxswain.TAG, "Scan 3");
             scanner.startScan(DEVICES_FILTER, DISCOVERY_SCAN, listener);
             Log.i(Coxswain.TAG, "Scan 4");
             return new ScanDestroyer(scanner, listener);
         } else {
             return Destroyable.NULL;
         }
    }

    /**
     * Finds a known device.
     * In order to stop the scan, cancel the returned Future. Else the scan will
     * continue indefinitely consuming high energy.
     *
     * @param deviceId  The bluetooth-address of the device
     */
    public Future<BluetoothHeartDevice> find(final String deviceId) {
        final CompletableFuture<BluetoothHeartDevice> future = new CompletableFuture<>();
        final Optional<BluetoothAdapter> adapter = getBluetoothAdapter();
        final BluetoothHeartDeviceFactory deviceFactory = new BluetoothHeartDeviceFactory(context);

        if (adapter.isPresent()) {
            final OnDevice listener = new OnDevice(
                    new BluetoothHeartDiscoveryListener() {
                        @Override
                        public void onDiscovered(BluetoothDevice device, int SignalStrength, boolean supportsConnectionLess) {
                            future.complete(
                                (supportsConnectionLess) ?
                                    deviceFactory.makeConnectionLess(device) :
                                    deviceFactory.make(device));
                        }

                        @Override
                        public void onLost(String deviceId) {

                        }
                    });

            final BluetoothLeScanner scanner = adapter.get().getBluetoothLeScanner();

            scanner.startScan(
                    ImmutableList.of(new ScanFilter.Builder()
                            .setDeviceAddress(deviceId)
                            .build()),
                    new ScanSettings.Builder()
                            .setNumOfMatches(1)
                            .setScanMode(SCAN_MODE_LOW_LATENCY)
                            .build(),
                    listener);

            return mixInCancel(future, scanner, listener);
        } else {
            future.completeExceptionally(new IllegalStateException("Unable to get BluetoothAdapter"));
        }

        return future;
    }

    /**
     *  Retrieves an adapter instance or asks to enable bluetooth
     */
    private Optional<BluetoothAdapter> getBluetoothAdapter() {
        final BluetoothManager mgr = context.getSystemService(BluetoothManager.class);
        final BluetoothAdapter adapter = mgr.getAdapter();

        // TODO: We can send early replies using mgr.getConnectedDevices(GATT)

        if (adapter == null || ! adapter.isEnabled()) {
            Log.w(Coxswain.TAG, "Bluetooth disabled or unavailable");
            final Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            context.startActivity(enableBtIntent);
            return Optional.empty();
        //} else if (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
        //    Log.w(Coxswain.TAG, "Bluetooth doesn't have LE features");
        //    // TODO: Report LE not available
        //    return Optional.empty();
        } else {
            return Optional.of(adapter);
        }
    }

    /**
     *  Allows a search for a single device (using find()) to be cancelled.
     */
    private <T> CompletableFuture<T> mixInCancel(final CompletableFuture<T> future, final BluetoothLeScanner scanner, final ScanCallback listener) {
        final CompletableFuture<T> ret = new CompletableFuture<T>() {
                @Override
                public boolean cancel(boolean mayInterruptIfRunning) {
                    super.cancel(mayInterruptIfRunning);
                    scanner.stopScan(listener);
                    return true;
                }
            };
        future.whenCompleteAsync(new BiConsumer<T, Throwable>() {
            @Override
            public void accept(T dev, Throwable exception) {
                if (dev != null) {
                    ret.complete(dev);
                } else {
                    ret.completeExceptionally(exception);
                }
            }
        });

        return ret;
    }

    private static class OnDevice extends ScanCallback {
        private final BluetoothHeartDiscoveryListener handler;

        public OnDevice(final BluetoothHeartDiscoveryListener handler) {
            this.handler = handler;
        }

        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            switch (callbackType) {
                case CALLBACK_TYPE_ALL_MATCHES:
                case CALLBACK_TYPE_FIRST_MATCH:
                    final byte[] heartRate = result.getScanRecord().getServiceData(BluetoothHeartServices.HEART_RATE.getParcelUuid());
                    handler.onDiscovered(result.getDevice(), result.getRssi(), heartRate != null);
                    break;
                case CALLBACK_TYPE_MATCH_LOST:
                    handler.onLost(result.getDevice().getAddress());
                    break;
                default:
                    Log.e(Coxswain.TAG, "Ignoring unexpected callback-type " + callbackType);
            }
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult result : results) {
                final byte[] heartRate = result.getScanRecord().getServiceData(BluetoothHeartServices.HEART_RATE.getParcelUuid());
                handler.onDiscovered(result.getDevice(), result.getRssi(), heartRate != null);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
        }
    }

    public static class ScanDestroyer implements Destroyable {
        private final BluetoothLeScanner scanner;
        private final OnDevice handler;

        public ScanDestroyer(final BluetoothLeScanner scanner, final OnDevice handler) {
            this.scanner = scanner;
            this.handler = handler;
        }

        public void destroy() {
            scanner.stopScan(handler);
        }
    }
}
