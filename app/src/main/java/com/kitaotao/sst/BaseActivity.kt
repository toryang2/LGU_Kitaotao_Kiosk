package com.kitaotao.sst


import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.RoundedBitmapDrawable
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import br.tiagohm.markdownview.MarkdownView
import com.kitaotao.sst.office.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.compass.CompassOverlay
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.net.HttpURLConnection
import java.net.URL

@OptIn(UnstableApi::class)
open class BaseActivity : AppCompatActivity() {

    private var exoPlayer: ExoPlayer? = null
    protected var idleTimeout: Long = 600000 // Default: 10 minutes
    protected var updateIdleTimeoutSeconds: Boolean = false
    protected var updateIdleTimeoutMinutes: Boolean = true
    protected var screensaverEnabled: Boolean = true
    val sharedPreferences by lazy {
        getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)
    }
    private var idleHandler: Handler? = null
    private val idleRunnable = Runnable {
        if(screensaverEnabled) {
            // Trigger screensaver after timeout
            val intent = Intent(this, postScreen::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()  // Close the current activity
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        loadPreferences()

        idleHandler = Handler(Looper.getMainLooper())
        resetIdleTimer()

        initializeOSMDroid()

        playAudioForActivity()

        Configuration.getInstance().userAgentValue = packageName
    }

    fun setPopupMenuWidth(popupMenu: PopupMenu, widthPx: Int) {
        try {
            val mPopup: Field = PopupMenu::class.java.getDeclaredField("mPopup")
            mPopup.isAccessible = true
            val menuPopupHelper = mPopup.get(popupMenu)

            val setWidthMethod: Method = menuPopupHelper.javaClass.getDeclaredMethod("setForceShowIcon", Boolean::class.java)
            setWidthMethod.isAccessible = true
            setWidthMethod.invoke(menuPopupHelper, true)

            // Use reflection to set width (field name may vary across Android versions)
            val popupMenuWidthField: Field = menuPopupHelper.javaClass.getDeclaredField("mMenuPopup")
            popupMenuWidthField.isAccessible = true
            val menuPopup = popupMenuWidthField.get(menuPopupHelper)

            val setWidthMethodPopup: Method = menuPopup.javaClass.getDeclaredMethod("setWidth", Int::class.java)
            setWidthMethodPopup.isAccessible = true
            setWidthMethodPopup.invoke(menuPopup, widthPx)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    protected fun loadPreferences() {
        screensaverEnabled = sharedPreferences.getBoolean("screensaverEnabled", true)
        updateIdleTimeoutSeconds = sharedPreferences.getBoolean("updateIdleTimeoutSeconds", false)
        updateIdleTimeoutMinutes = sharedPreferences.getBoolean("updateIdleTimeoutMinutes", true)
        idleTimeout = sharedPreferences.getLong("idleTimeout", 600000)
    }

    protected fun savePreferences(sharedPreferences: SharedPreferences, lastSelectedTimeoutText: String, dropDownButton: Button? = null) {
        with(sharedPreferences.edit()) {
            putBoolean("screensaverEnabled", screensaverEnabled)
            putBoolean("updateIdleTimeoutSeconds", updateIdleTimeoutSeconds)
            putBoolean("updateIdleTimeoutMinutes", updateIdleTimeoutMinutes)
            putLong("idleTimeout", idleTimeout)
            putString("lastSelectedTimeout", lastSelectedTimeoutText)
            apply()
        }
    }

    protected fun updateIdleTimeout(seconds: Int = 0, minutes: Int = 0, dropDownButton: Button? = null) {
        idleTimeout = when {
            updateIdleTimeoutSeconds -> (seconds * 1000).toLong() // Convert seconds to milliseconds
            updateIdleTimeoutMinutes -> (minutes * 60 * 1000).toLong() // Convert minutes to milliseconds
            else -> 0L // Default to no timeout if neither is enabled
        }
        savePreferences(sharedPreferences, "Timeout: ${if (updateIdleTimeoutSeconds) "$seconds seconds" else "$minutes minutes"}", dropDownButton)
    }
//    fun updateProgressSmoothly(progress: Int) {
//        val animator = ObjectAnimator.ofInt(progressBar, "progress", progressBar.progress, progress)
//        animator.duration = 1000 // Duration in milliseconds (1 second)
//        animator.interpolator = AccelerateDecelerateInterpolator() // Smooth transition
//        animator.start()
//    }

    // Static API key (static value)
    companion object {
        const val API_KEY = "5816775e-afb6-4912-b64e-be7a1066183a"  // Replace with your actual API key
    }

    // Method to create the GraphHopper URL
    fun createGraphHopperUrl(firstMarkerPoint: GeoPoint, secondMarkerPoint: GeoPoint): String {
        return "https://graphhopper.com/api/1/route?point=${firstMarkerPoint.latitude},${firstMarkerPoint.longitude}&point=${secondMarkerPoint.latitude},${secondMarkerPoint.longitude}&vehicle=foot&key=$API_KEY"
    }

    // Function to decode polyline
    fun decodePolyline(encoded: String): List<GeoPoint> {
        val poly = ArrayList<GeoPoint>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lat += dlat

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lng += dlng

            val geoPoint = GeoPoint(lat / 1E5, lng / 1E5)
            poly.add(geoPoint)
        }
        return poly
    }

    protected fun createPolyline(
        mapView: MapView, // Pass the mapView as a parameter
        points: List<GeoPoint>,
        colorResId: Int,
        strokeWidth: Float,
        clickable: Boolean = true,
        dashed: Boolean = false
    ): Polyline {
        // Create the Polyline object
        val polyline = object : Polyline(mapView) {
            override fun onSingleTapConfirmed(e: MotionEvent?, mapView: MapView?): Boolean {
                return !clickable // Disable interaction if `clickable` is false
            }
        }

        // Apply the configuration to the polyline object
        polyline.apply {
            setPoints(points)
            outlinePaint.apply {
                color = ContextCompat.getColor(this@BaseActivity, colorResId)
                this.strokeWidth = strokeWidth
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                if (dashed) {
                    pathEffect = DashPathEffect(floatArrayOf(5f, 15f), 0f) // Dashed line
                }
            }
        }

        return polyline
    }


    protected lateinit var mapView: MapView

    fun getCacheFilePath(firstMarkerPoint: GeoPoint, secondMarkerPoint: GeoPoint): File {
        // Get the app's internal cache directory
        val cacheDir = File(getExternalFilesDir(null), "osmdroid/polyline")

        // Ensure the directory exists
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }

        // Create the cache file name
        val cacheFileName = "polyline_cache_${firstMarkerPoint.latitude}_${firstMarkerPoint.longitude}_${secondMarkerPoint.latitude}_${secondMarkerPoint.longitude}.txt"

        // Return the complete file path
        return File(cacheDir, cacheFileName)
    }

    fun savePolylineToCache(file: File, polylineData: String) {
        file.writeText(polylineData)
    }

    fun loadPolylineFromCache(file: File): String? {
        return if (file.exists()) {
            file.readText()
        } else {
            null
        }
    }

    fun fetchAndDisplayPolyline(firstMarkerPoint: GeoPoint, secondMarkerPoint: GeoPoint) {
        val cacheFile = getCacheFilePath(firstMarkerPoint, secondMarkerPoint)

        // Check if the polyline is already cached
        val cachedPolyline = loadPolylineFromCache(cacheFile)
        if (cachedPolyline != null) {
            // Decode the cached polyline and display it
            val decodedPoints = decodePolyline(cachedPolyline)
            displayPolylineOnMap(firstMarkerPoint, secondMarkerPoint, decodedPoints)
            return
        }

        // If not cached, fetch from GraphHopper API
        val urlString = createGraphHopperUrl(firstMarkerPoint, secondMarkerPoint)

        Thread {
            try {
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"

                val response = connection.inputStream.bufferedReader().readText()
                val jsonResponse = JSONObject(response)
                val paths = jsonResponse.getJSONArray("paths")

                if (paths.length() > 0) {
                    val route = paths.getJSONObject(0)
                    val encodedPolyline = route.getString("points")
                    val decodedPoints = decodePolyline(encodedPolyline)

                    // Save the polyline to cache
                    savePolylineToCache(cacheFile, encodedPolyline)

                    runOnUiThread {
                        displayPolylineOnMap(firstMarkerPoint, secondMarkerPoint, decodedPoints)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    fun displayPolylineOnMap(firstMarkerPoint: GeoPoint, secondMarkerPoint: GeoPoint, decodedPoints: List<GeoPoint>) {
        val overlays = mutableListOf<Polyline>()

        // Add the main route polyline
        overlays.add(createPolyline(mapView, decodedPoints, R.color.red, 10f, clickable = false))

        // Handle missing route segments
        val firstDecodedPoint = decodedPoints.first()
        val lastDecodedPoint = decodedPoints.last()

        // Add dashed polyline for missing segments
        if (firstDecodedPoint != firstMarkerPoint) {
            overlays.add(
                createPolyline(
                    mapView, listOf(firstMarkerPoint, firstDecodedPoint),
                    R.color.red, 8f, clickable = false, dashed = true
                )
            )
        }

        if (lastDecodedPoint != secondMarkerPoint) {
            overlays.add(
                createPolyline(
                    mapView, listOf(lastDecodedPoint, secondMarkerPoint),
                    R.color.red, 8f, clickable = false, dashed = true
                )
            )
        }

        // Add all overlays to the map
        mapView.overlays.addAll(overlays)
        mapView.invalidate() // Redraw the map
    }

    // Flag to track the state of the overlay image (whether it's enlarged or not)
    private var isEnlarged = false

    /**
     * This function should be called from the specific Activity (like TOURISM.kt)
     * to handle the overlay image functionality.
     */
    fun setupOverlayImage(view: View, overlayImage: ImageView) {
        overlayImage.setOnClickListener {
            if (isEnlarged) {
                // If the image is enlarged, shrink it back
                shrinkImage(overlayImage)
            } else {
                // If the image is not enlarged, enlarge it with breathing effect
                enlargeImage(view, overlayImage)
            }
        }
    }

    fun startBreathingAnimation(linearLayout: LinearLayout) {
        // Define the start and end colors for the background
        val startBackgroundColor = Color.parseColor("#FFFFFF")  // White color
        val endBackgroundColor = Color.parseColor("#4CAF50")    // Green color

        // Stroke color remains constant
        val strokeColor = Color.parseColor("#000000")  // Black stroke color

        // Create the color animator for the background color
        val backgroundColorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), startBackgroundColor, endBackgroundColor)
        backgroundColorAnimator.duration = 1000 // Duration for one cycle (1 second)
        backgroundColorAnimator.repeatMode = ValueAnimator.REVERSE  // Reverse the animation after each cycle
        backgroundColorAnimator.repeatCount = ValueAnimator.INFINITE // Infinite repetitions to create continuous effect

        // Apply the background color animation
        backgroundColorAnimator.addUpdateListener { animator ->
            val animatedBackgroundColor = animator.animatedValue as Int
            // Set the background color dynamically
            linearLayout.setBackgroundColor(animatedBackgroundColor)
        }

        // Create the drawable with rounded corners and stroke
        val roundedDrawable = createRoundedDrawable(strokeColor)

        // Set the background with rounded corners and stroke
        linearLayout.background = roundedDrawable

        // Start the background color animation
        backgroundColorAnimator.start()
    }

    fun createRoundedDrawable(strokeColor: Int): LayerDrawable {
        // Create a GradientDrawable with rounded corners and background color
        val roundedCorners = GradientDrawable()
        roundedCorners.cornerRadius = 10f // 10dp corner radius (you can convert dp to pixels if needed)
        roundedCorners.setColor(Color.parseColor("#FFFFFF")) // Background color

        // Convert stroke width from dp to pixels and apply it
        val strokeWidthInPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2f, resources.displayMetrics).toInt()
        roundedCorners.setStroke(strokeWidthInPx, strokeColor) // Set the stroke with width and color

        // Return the rounded drawable
        return LayerDrawable(arrayOf(roundedCorners))
    }

    protected fun applyRoundedCorners(overlayImage: ImageView) {
        // Get the current drawable from the ImageView
        val drawable = overlayImage.drawable

        if (drawable is BitmapDrawable) {
            // If the drawable is a BitmapDrawable, we can apply rounded corners
            val bitmap = drawable.bitmap
            val roundedDrawable: RoundedBitmapDrawable = RoundedBitmapDrawableFactory.create(overlayImage.resources, bitmap)

            // Set the corner radius for the rounded corners
            roundedDrawable.cornerRadius = 40f  // Adjust the corner radius as needed

            // Set the rounded drawable as the image for the ImageView
            overlayImage.setImageDrawable(roundedDrawable)
        }
    }


    /**
     * Enlarge the overlay image with a breathing effect.
     */
    private fun enlargeImage(view: View, overlayImage: ImageView) {
        // Get the parent view's dimensions (CardView's dimensions or the VideoView's dimensions)
        val parentWidth = (view.parent as View).width
        val parentHeight = (view.parent as View).height

        // Get the original width and height of the overlay image
        val originalWidth = overlayImage.width
        val originalHeight = overlayImage.height

        // Set the enlarged size to match the parent view's dimensions
        val enlargedWidth = parentWidth
        val enlargedHeight = parentHeight

        // Animate the image resize with breathing effect and overlap
        animateImageResizeWithBreathing(overlayImage, originalWidth, originalHeight, enlargedWidth, enlargedHeight)

        // Set the flag to indicate the image is enlarged
        isEnlarged = true
    }


    /**
     * Shrink the overlay image back to its original size.
     */
    private fun shrinkImage(overlayImage: ImageView) {
        // Convert 100dp to pixels
        val context = overlayImage.context
        val widthInPixels = (100 * context.resources.displayMetrics.density).toInt()  // Convert dp to pixels

        // Get the current width and height of the image
        val originalWidth = overlayImage.layoutParams.width
        val originalHeight = overlayImage.layoutParams.height

        // Create a ValueAnimator to animate the size change from the current size to the new smaller size
        val animatorWidth = ValueAnimator.ofInt(originalWidth, widthInPixels)
        val animatorHeight = ValueAnimator.ofInt(originalHeight, widthInPixels)

        // Update listener for width and height
        animatorWidth.addUpdateListener { animation ->
            val animatedValue = animation.animatedValue as Int
            overlayImage.layoutParams.width = animatedValue
            overlayImage.requestLayout()  // Request layout to apply changes
        }

        animatorHeight.addUpdateListener { animation ->
            val animatedValue = animation.animatedValue as Int
            overlayImage.layoutParams.height = animatedValue
            overlayImage.requestLayout()  // Request layout to apply changes
        }

        // Set the animation duration (300ms)
        animatorWidth.duration = 300
        animatorHeight.duration = 300

        // Start the animations for both width and height
        animatorWidth.start()
        animatorHeight.start()

        // Set the flag to indicate the image is not enlarged
        isEnlarged = false
    }

    /**
     * Animates the resizing of the overlay image, with a breathing effect.
     * It briefly expands the image slightly beyond the target size, then shrinks back to the correct size.
     */
    private fun animateImageResizeWithBreathing(
        overlayImage: ImageView,
        startWidth: Int,
        startHeight: Int,
        endWidth: Int,
        endHeight: Int
    ) {
        // If the image is already enlarged, return early
        if (isEnlarged) {
            // Shrink the image if it's already enlarged
            shrinkImage(overlayImage)
            return
        }

        // Otherwise, proceed with enlarging the image
        val breathingWidth = (endWidth * 1.1).toInt()  // Slightly increase width (10% larger)
        val breathingHeight = (endHeight * 1.1).toInt()  // Slightly increase height (10% larger)

        // First animation: Expand to slightly larger size
        val firstAnimator = ValueAnimator.ofFloat(0f, 1f)
        firstAnimator.addUpdateListener { firstAnimation ->
            val progress = firstAnimation.animatedFraction
            val newWidth = startWidth + (breathingWidth - startWidth) * progress
            val newHeight = startHeight + (breathingHeight - startHeight) * progress

            overlayImage.layoutParams.width = newWidth.toInt()
            overlayImage.layoutParams.height = newHeight.toInt()
            overlayImage.requestLayout()
        }
        firstAnimator.duration = 150 // Shorter duration for initial "breathing" phase
        firstAnimator.start()

        // Second animation: Shrink to the correct target size
        firstAnimator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                val secondAnimator = ValueAnimator.ofFloat(0f, 1f)
                secondAnimator.addUpdateListener { secondAnimation ->
                    val progress = secondAnimation.animatedFraction
                    val newWidth = breathingWidth + (endWidth - breathingWidth) * progress
                    val newHeight = breathingHeight + (endHeight - breathingHeight) * progress

                    overlayImage.layoutParams.width = newWidth.toInt()
                    overlayImage.layoutParams.height = newHeight.toInt()
                    overlayImage.requestLayout()
                }
                secondAnimator.duration = 150 // Duration for the shrinking back to the final size
                secondAnimator.start()

                // Set the flag to indicate the image is enlarged
                isEnlarged = true
            }
        })
    }

    fun initializeMap(mapView: MapView) {
        // Enable pinch zoom
        mapView.setMultiTouchControls(true)

        // Disable zoom controls (plus/minus buttons)
        mapView.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)

        mapView.setTileSource(object : OnlineTileSourceBase(
            "GoogleMaps_Satellite",
            0, 20, 256, "",
            arrayOf("https://mt1.google.com/vt/lyrs=s&x={x}&y={y}&z={z}")
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                val zoom = MapTileIndex.getZoom(pMapTileIndex)
                val x = MapTileIndex.getX(pMapTileIndex)
                val y = MapTileIndex.getY(pMapTileIndex)
                return "https://mt1.google.com/vt/lyrs=s&x=$x&y=$y&z=$zoom"
            }
        })
    }

    // Function to calculate the distance between two geo points
    fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371 // Radius of the Earth in km
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return earthRadius * c // Distance in km
    }

    // Function to adjust the padding based on the distance between markers
    fun getDynamicPadding(distance: Double): Double {
        return when {
            distance < 0.1 -> 0.0003 // Close proximity, small padding
            distance < 0.5 -> 0.0005 // Medium proximity, slightly more padding
            distance < 1.0 -> 0.002  // Medium proximity, even more padding
            else -> 0.002           // Farther apart, larger padding
        }
    }

    // Move the logic to a function so it can be called in subclasses
    fun adjustMapViewForMarkers(mapView: MapView, firstMarker: Marker, secondMarker: Marker) {
        // Calculate the distance between the two markers
        val distance = calculateDistance(
            firstMarker.position.latitude,
            firstMarker.position.longitude,
            secondMarker.position.latitude,
            secondMarker.position.longitude
        )

        // Adjust the title padding factor based on the distance between the markers
        val titlePaddingFactor = getDynamicPadding(distance)

        val centerLatitude = (firstMarker.position.latitude + secondMarker.position.latitude) / 2
        val centerLongitude = (firstMarker.position.longitude + secondMarker.position.longitude) / 2

        // Adjust bounding box based on marker positions, dynamic title padding, and center
        val adjustedBoundingBox = BoundingBox(
            maxOf(firstMarker.position.latitude, secondMarker.position.latitude) + titlePaddingFactor,
            maxOf(firstMarker.position.longitude, secondMarker.position.longitude) + titlePaddingFactor,
            minOf(firstMarker.position.latitude, secondMarker.position.latitude) - titlePaddingFactor,
            minOf(firstMarker.position.longitude, secondMarker.position.longitude) - titlePaddingFactor
        )

        // Adjust the map to fit the adjusted bounding box with more padding
        mapView.post {
            mapView.zoomToBoundingBox(
                adjustedBoundingBox.increaseByScale(1.2F), // Scale by 20% for more padding
                false // Animated transition
            )
            // Recalculate and update the map center after adjustments
            mapView.controller.setCenter(GeoPoint(centerLatitude, centerLongitude))
        }

        // Add a CompassOverlay
        val compassOverlay = CompassOverlay(mapView.context, mapView)
        compassOverlay.enableCompass()
        mapView.overlays.add(compassOverlay)
    }




    private fun initializeOSMDroid() {
        // OSMDroid configuration
        val configuration = Configuration.getInstance()

        // Set user agent dynamically based on your app
        configuration.userAgentValue = packageName

        // Set up the cache directory for tiles
        val cacheDir = File(getExternalFilesDir(null), "osmdroid/tiles")
        configuration.osmdroidTileCache = cacheDir

        // Configure tile cache size
        configuration.tileFileSystemCacheTrimBytes = 200L * 1024 * 1024 // 200 MB
        configuration.tileFileSystemCacheMaxBytes = 500L * 1024 * 1024 // 500 MB

        // Set expiration time for cached tiles (e.g., 7 days)
        configuration.expirationOverrideDuration = 1L * 24 * 60 * 60 * 1000 // 1 day
    }

    // Reset the timer whenever there's user interaction
    override fun onUserInteraction() {
        super.onUserInteraction()
        resetIdleTimer()
    }

    override fun onResume() {
        super.onResume()
        loadPreferences()
        // Reset idle timeout when the activity is resumed
        idleHandler?.removeCallbacks(idleRunnable)
        idleHandler?.postDelayed(idleRunnable, idleTimeout)
    }

    override fun onPause() {
        super.onPause()
        // Stop the idle timeout when the activity is paused
        idleHandler?.removeCallbacks(idleRunnable)
    }

    fun resetIdleTimer() {
        idleHandler?.removeCallbacks(idleRunnable)
        idleHandler?.postDelayed(idleRunnable, idleTimeout)
    }

    override fun onDestroy() {
        super.onDestroy()
        idleHandler?.removeCallbacks(idleRunnable)
        exoPlayer?.release()
    }

    private fun playAudioForActivity() {
        // Check the current activity and associate it with the correct audio resource
        val audioResId = when {
            this is BidsAndAwardsCommittee -> R.raw.bac_sound
            this is HumanResourceManagementOffice -> R.raw.hrmo_sound
            this is IPMR -> R.raw.ipmr_sound
            this is KitaotaoWaterSystem -> R.raw.water_sound
            this is LEDIPO -> R.raw.ledipo_sound
            this is LIGA -> R.raw.liga_sound
            this is LYDO -> R.raw.lydo_sound
            this is MDRRMO -> R.raw.mdrrmo_sound
            this is MENRO -> R.raw.menro_sound
            this is MPDO -> R.raw.mpdo_sound
            this is MSWDO -> R.raw.mswdo_sound
            this is MunicipalAccountingOffice -> R.raw.macco_sound
            this is MunicipalAdministratorOffice -> R.raw.admin_sound
            this is MunicipalAgricultureOffice -> R.raw.magro_sound
            this is MunicipalAssessorsOffice -> R.raw.assessor_sound
            this is MunicipalBudgetOffice -> R.raw.budget_sound
            this is MunicipalCivilRegistryOffice -> R.raw.mcro_sound
            this is MunicipalEngineeringOffice -> R.raw.engineering_sound
            this is MunicipalGeneralServiceOffice -> R.raw.gso_sound
            this is MunicipalHealthOffice -> R.raw.health_sound
            this is MunicipalMayorOffice -> R.raw.mayor_sound
            this is PESO -> R.raw.peso_sound
            this is POPDEV -> R.raw.popdev_sound
            this is PWD -> R.raw.pwd_sound
            this is SBO -> R.raw.sbo_sound
            this is SENIOR -> R.raw.osca_sound
            this is TOURISM -> R.raw.tourism_sound
            this is TREASURER -> R.raw.treasurer_sound
            this is MunicipalBusinessProcessingAndLicensingOffice -> R.raw.bplo_sound
            else -> null // No audio for other activities
        }

        // If a valid audio resource was found, play it
        audioResId?.let {
            playAudio(it)
        }
    }

    private fun playAudio(audioResId: Int) {
        // Create MediaItem with audio resource URI
        val mediaItem = MediaItem.fromUri(Uri.parse("android.resource://${packageName}/$audioResId"))

        // Create the DataSource.Factory (Non-deprecated way)
        val dataSourceFactory = DefaultDataSource.Factory(this)

        // Create the MediaSource using DefaultMediaSourceFactory
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        // Initialize ExoPlayer
        exoPlayer = ExoPlayer.Builder(this).build()

        // Prepare the media and start playback
        exoPlayer?.setMediaSource(mediaSourceFactory.createMediaSource(mediaItem))
        exoPlayer?.prepare()
        exoPlayer?.playWhenReady = true

        // Add the player listener for playback state and error handling
        exoPlayer?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    exoPlayer?.release() // Release resources when done
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // Handle the error when playback fails
                exoPlayer?.release() // Release resources on error
            }
        })
    }

    fun showMarkdownDialog(markdownContent: String) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_markdown, null)
        val markdownView = dialogView.findViewById<MarkdownView>(R.id.markdownView)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Close") { dialog, _ -> dialog.dismiss() }
            .create()

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)

            val color = Color.parseColor("#49454F")
            positiveButton.setTextColor(color)
        }

        markdownView.loadMarkdown(markdownContent)
        dialog.show()
    }

    // Fetch markdown content and manage local cache
    fun fetchMarkdownFile(url: String, onResult: (String?) -> Unit) {
        val client = OkHttpClient()
        val request = Request.Builder().url(url).build()

        Thread {
            try {
                // Fetch from the URL
                val response = client.newCall(request).execute()
                val remoteMarkdown = response.body?.string()

                if (remoteMarkdown != null) {
                    val cachedMarkdown = readMarkdownFromFile()

                    if (cachedMarkdown == null || cachedMarkdown != remoteMarkdown) {
                        // Update the cache if content has changed
                        saveMarkdownToFile(remoteMarkdown)
                    }
                    onResult(remoteMarkdown) // Return the latest content
                } else {
                    // Fallback to cache if fetching fails
                    val cachedMarkdown = readMarkdownFromFile()
                    onResult(cachedMarkdown)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // On error, fallback to cache
                val cachedMarkdown = readMarkdownFromFile()
                onResult(cachedMarkdown)
            }
        }.start()
    }

    // Save markdown content to local file
    fun saveMarkdownToFile(content: String) {
        try {
            val fileOutputStream: FileOutputStream = openFileOutput("readme.md", MODE_PRIVATE)
            fileOutputStream.write(content.toByteArray())
            fileOutputStream.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    // Read markdown content from local file
    fun readMarkdownFromFile(): String? {
        try {
            val fileInputStream: FileInputStream = openFileInput("readme.md")
            return fileInputStream.bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return null
    }
}
