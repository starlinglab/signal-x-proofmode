package org.thoughtcrime.securesms.conversation

import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.thoughtcrime.securesms.mediasend.ProofModeUtil

const val DEFAULT_LAT = 40.7128
const val DEFAULT_LON = -74.0060
const val DEFAULT_INTERVAL = 10L
const val DEFAULT_DISPLACEMENT = 10F

// check the provider name to see whether locations came from missing permissions
const val NO_PERMS_PROVIDER_NAME = "Permissions not granted"

interface LocationSource {
    suspend fun awaitLastLocation(): Location
    fun getLocationUpdates(): Flow<Location>
}

@Suppress("MissingPermission")
@ExperimentalCoroutinesApi
class NativeLocationSource(private val context: Context, private val client: FusedLocationProviderClient) :
    LocationSource {

    private val mLocationRequest = LocationRequest.create().apply {
        interval = DEFAULT_INTERVAL
        smallestDisplacement = DEFAULT_DISPLACEMENT
        fastestInterval = DEFAULT_INTERVAL
    }

    override suspend fun awaitLastLocation(): Location = suspendCancellableCoroutine { continuation ->
        if (!locationPermissionsAllowed(context)) {
            continuation.resume(NO_PERMS_LOCATION) {}
        }
        client.lastLocation.addOnSuccessListener { location ->
            continuation.resume(location) {}
        }.addOnFailureListener { e ->
            continuation.resumeWithException(e)
        }
    }

    override fun getLocationUpdates(): Flow<Location> = callbackFlow {
        if (!locationPermissionsAllowed(context)) {
            trySend(NO_PERMS_LOCATION).isSuccess
            close()
        }
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (location in result.locations) {
                    try {
                        this@callbackFlow.trySend(location).isSuccess
                        close()
                    } catch (t: Throwable) {
//                        Timber.w(t, "Location couldn't be sent to the flow")
                        close(t)
                    }
                }
            }
        }
        client.requestLocationUpdates(mLocationRequest, callback, Looper.getMainLooper())
            .addOnFailureListener { t ->
                close(t)
            }

        awaitClose { client.removeLocationUpdates(callback) }
    }

    private fun locationPermissionsAllowed(context: Context): Boolean {
        return ProofModeUtil.hasAnyPermission(context, ProofModeUtil.LOCATION)
    }

    companion object {
        private val NO_PERMS_LOCATION: Location = Location(NO_PERMS_PROVIDER_NAME).apply {
            latitude = DEFAULT_LAT
            longitude = DEFAULT_LON
        }
    }
}
