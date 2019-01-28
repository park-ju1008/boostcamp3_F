package com.boostcamp.travery.mapservice

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.location.Location
import android.os.IBinder
import androidx.core.app.NotificationCompat

import android.location.LocationManager
import android.util.Log
import android.content.Context
import android.os.Binder
import com.boostcamp.travery.MyApplication.Companion.CHANNEL_ID
import com.boostcamp.travery.data.model.Route
import android.os.Looper
import com.boostcamp.travery.main.MainActivity
import com.google.android.gms.location.*
import com.google.android.gms.maps.model.LatLng
import com.boostcamp.travery.R

@SuppressLint("Registered")
class MapTrackingService : Service(), MapTrackingContract.Model {

    private val mFusedLocationClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(
            this
        )
    }

    private val locationRequest: LocationRequest by lazy {
        LocationRequest()
            .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
            .setInterval(UPDATE_INTERVAL_MS)
            .setFastestInterval(FASTEST_UPDATE_INTERVAL_MS)
    }

    private val UPDATE_INTERVAL_MS: Long = 2500  // 1초
    private val FASTEST_UPDATE_INTERVAL_MS: Long = 1500 //
    private var lostLocationCnt = 0

    private val locationList: ArrayList<LatLng> = ArrayList()
    private val timeList: ArrayList<Long> = ArrayList()
    private var canSuggest = true
    private val suggestList: ArrayList<LatLng> = ArrayList()
    private val TAG = "MyLocationService"

    private var startTime:Int ?= null
    private var exLocation: Location? = null
    private var totalDistance = 0f
    var isRunning = false
    private var second: Int = 0
    private var countThread: Thread? = null
    private var mCallback: ICallback? = null
    private val mLocationManager: LocationManager by lazy { getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    private val notification: NotificationCompat.Builder by lazy {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0, notificationIntent, 0
        )
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_title))
            .setContentText(getString(R.string.service_message))
            .setSmallIcon(R.drawable.ic_play_circle_filled_black_60dp)
            .setContentIntent(pendingIntent)
    }
    private val mBinder = LocalBinder()

    internal inner class LocalBinder : Binder() {
        val service: MapTrackingService
            get() = this@MapTrackingService
    }

    private var locationCallback: LocationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            super.onLocationResult(locationResult)
            val nowLocationList = locationResult.locations
            if (nowLocationList.size > 0) {
                val location = nowLocationList.last()
                //location = locationList.get(0);
                //currentPosition = LatLng(location.getLatitude(), location.getLongitude())
                Log.d(TAG, "onLocationResult : " + location.accuracy)

                if (exLocation != null) {
                    val dis = location.distanceTo(exLocation)
                    //이동거리가 1m 이상 10m 이하이고 오차범위가 10m 미만일 때
                    //실내에서는 12m~30m정도의 오차 발생
                    //야외에서는 3m~11m정도의 오차 발생
                    if (dis >= 1 && dis <10 && location.accuracy < 9.5) {
                        totalDistance += location.distanceTo(exLocation)
                        val locate = LatLng(location.latitude, location.longitude)
                        locationList.add(locate)
                        timeList.add(location.time)
                        mCallback?.sendData(locate, location.accuracy)
                        exLocation = location

                        if(lostLocationCnt > 60 && canSuggest){
                            suggestList.add(locate)
                        }
                        canSuggest = true
                        lostLocationCnt=0
                    }else{
                        lostLocationCnt++
                    }

                    //Log.d(TAG, "onLocationResult: ${totalDistance}")
                } else {
                    exLocation = location
                }
                //Log.d(TAG, "onLocationChanged: ${location.time}")

                //시간
                /*val format = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.KOREA)
                val formatted = format.format(location.time)
                Log.d(TAG, "onLocationChanged: $formatted")*/

                //mLastLocation.set(location)]
                //if (mCallback != null) {

            }
        }
    }

    @SuppressLint("CheckResult")
    override fun onCreate() {

        Log.e(TAG, "onCreate")

        val builder = LocationSettingsRequest.Builder()
        builder.addLocationRequest(locationRequest)


        try {
            mFusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.myLooper())

        } catch (ex: java.lang.SecurityException) {
            Log.i(TAG, "fail to request location update, ignore", ex)
        } catch (ex: IllegalArgumentException) {
            Log.d(TAG, "network provider does not exist, " + ex.message)
        }
    }


    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {

        startForeground(1, notification.build())

        isRunning = true

        if (countThread == null) {
            countThread = Thread(Counter())
            countThread!!.start()
        }

        startTime = System.currentTimeMillis().toInt()

        return Service.START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun getLastKnownLocation(): Location? {
        val providers = mLocationManager.getProviders(true)
        var bestLocation: Location? = null
        for (provider in providers) {
            val l = mLocationManager.getLastKnownLocation(provider) ?: continue
            if (bestLocation == null || l.accuracy < bestLocation.accuracy) {
                // Found best last known location: %s", l);
                bestLocation = l
            }
        }
        if (isRunning && bestLocation != null) {
            locationList.add(LatLng(bestLocation.latitude, bestLocation.longitude))
            timeList.add(bestLocation.time)
        }
        return bestLocation
    }

    override fun onDestroy() {
        Log.e(TAG, "onDestroy")
        super.onDestroy()
        countThread = null
        isRunning = false
        if (mFusedLocationClient != null) {
            Log.d(TAG, "onStop : call stopLocationUpdates");
            mFusedLocationClient.removeLocationUpdates(locationCallback)
        }

    }

    inner class Counter : Runnable {
        override fun run() {
            second = 0
            while (true) {
                if (!isRunning) {
                    break
                }

                try {
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                }
                second++
            }
        }
    }

    interface ICallback {
        fun sendData(location: LatLng, accuracy: Float)
    }

    fun registerCallback(cb: ICallback) {
        mCallback = cb
    }

    override fun getTotalSecond(): Int {
        return second
    }

    override fun getFinishData(): Route {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun getLastLocation(): Location? {

        return getLastKnownLocation()
    }

    override fun getLocationList(): ArrayList<LatLng> {
        return locationList
    }

    override fun onBind(intent: Intent): IBinder? {
        return mBinder
    }
}