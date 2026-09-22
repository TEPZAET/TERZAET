package io.github.p1neapplexpress.openflux.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity.RESULT_OK
import android.net.VpnService
import android.net.Uri
import android.media.MediaPlayer
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.TrafficStats
import android.os.SystemClock
import android.os.Bundle
import android.content.res.ColorStateList
import android.view.HapticFeedbackConstants
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanQRCode
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.data.Tunnel
import io.github.p1neapplexpress.openflux.data.ServerRelease
import io.github.p1neapplexpress.openflux.data.TunnelState
import io.github.p1neapplexpress.openflux.event.AppEvent
import io.github.p1neapplexpress.openflux.ui.widget.AuroraView
import io.github.p1neapplexpress.openflux.ui.widget.CropVideoView
import io.github.p1neapplexpress.openflux.ui.widget.PulseRingsView
import io.github.p1neapplexpress.openflux.util.toUptimeHms
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import java.util.Locale

class TunnelsFragment : BaseFragment() {

    private val vm: TunnelsViewModel by activityViewModels()

    private lateinit var aurora: AuroraView
    private lateinit var pulseRings: PulseRingsView
    private lateinit var ringOuter: View
    private lateinit var ringMid: View
    private lateinit var connectButton: View
    private lateinit var powerIcon: ImageView
    private lateinit var configSelector: View
    private lateinit var configDot: View
    private lateinit var tunnelName: TextView
    private lateinit var chevron: ImageView
    private lateinit var statusText: TextView
    private lateinit var headerStatus: TextView
    private lateinit var connectLabel: TextView
    private lateinit var transitionVideo: CropVideoView
    private lateinit var videoPoster: ImageView
    private var transitionPlayer: MediaPlayer? = null
    private lateinit var speedValue: TextView
    private lateinit var pingValue: TextView
    private var metricsJob: Job? = null

    override fun onResume() {
        super.onResume()
        vm.reconcileSystemState()
    }
    private var videoEnabled = true
    private var hapticsEnabled = true
    private var motionEnabled = true
    private var energySaving = false
    private var videoAlpha = 1f
    private lateinit var uptimeText: TextView

    private var rotationAnim: ObjectAnimator? = null
    private var breathAnim: ObjectAnimator? = null
    private var currentVisualState: TunnelState? = null
    private var popup: PopupWindow? = null

    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) vm.startCurrent()
        else Toast.makeText(requireContext(), R.string.vpn_permission_required, Toast.LENGTH_LONG).show()
    }

    private val qrScanner = registerForActivityResult(ScanQRCode()) { result ->
        val raw = (result as? QRResult.QRSuccess)?.content?.rawValue
            ?: return@registerForActivityResult
        runCatching { Json.decodeFromString<Tunnel>(raw) }
            .onSuccess { vm.addTunnel(it); vm.startTunnel(it) }
            .onFailure {
                Toast.makeText(requireContext(), R.string.qr_scan_failed, Toast.LENGTH_LONG).show()
            }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?) =
        i.inflate(R.layout.fragment_tunnels, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val uiPrefs = requireContext().getSharedPreferences("ui_settings", 0)
        videoEnabled = true
        hapticsEnabled = true
        motionEnabled = true
        energySaving = false
        videoAlpha = uiPrefs.getInt("video_intensity", 100).coerceIn(20, 100) / 100f

        aurora = view.findViewById(R.id.aurora)
        pulseRings = view.findViewById(R.id.pulseRings)
        ringOuter = view.findViewById(R.id.ringOuter)
        ringMid = view.findViewById(R.id.ringMid)
        connectButton = view.findViewById(R.id.connectButton)
        powerIcon = view.findViewById(R.id.powerIcon)
        configSelector = view.findViewById(R.id.configSelector)
        configDot = view.findViewById(R.id.configDot)
        tunnelName = view.findViewById(R.id.tunnelName)
        chevron = view.findViewById(R.id.chevron)
        statusText = view.findViewById(R.id.statusText)
        headerStatus = view.findViewById(R.id.headerStatus)
        connectLabel = view.findViewById(R.id.connectLabel)
        transitionVideo = view.findViewById(R.id.transitionVideo)
        videoPoster = view.findViewById(R.id.videoPoster)
        if (videoEnabled) {
            val retriever = MediaMetadataRetriever()
            runCatching {
                resources.openRawResourceFd(R.raw.woman_glass_hq).use { source ->
                    retriever.setDataSource(source.fileDescriptor, source.startOffset, source.length)
                    videoPoster.setImageBitmap(retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC))
                }
            }
            retriever.release()
        } else {
            videoPoster.visibility = View.GONE
        }
        transitionVideo.setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE)
        transitionVideo.setVideoURI(Uri.parse("android.resource://${requireContext().packageName}/${R.raw.woman_glass_hq}"))
        transitionVideo.setOnInfoListener { _, what, _ ->
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                if (motionEnabled) {
                    videoPoster.animate().alpha(0f).setDuration(180L).withEndAction {
                        videoPoster.visibility = View.GONE
                    }.start()
                } else {
                    videoPoster.visibility = View.GONE
                }
            }
            false
        }
        transitionVideo.setOnPreparedListener { player ->
            transitionPlayer = player
            if (!videoEnabled) {
                player.pause()
                transitionVideo.visibility = View.GONE
                return@setOnPreparedListener
            }
            player.isLooping = currentVisualState !is TunnelState.Running && !energySaving
            player.setVolume(0f, 0f)
            transitionVideo.setSourceSize(player.videoWidth, player.videoHeight)
            transitionVideo.visibility = View.VISIBLE
            transitionVideo.alpha = videoAlpha
            transitionVideo.start()
        }
        transitionVideo.setOnCompletionListener { player ->
            if (currentVisualState is TunnelState.Running) {
                player.pause()
            } else {
                player.isLooping = !energySaving
                if (!energySaving) restartBackgroundVideo()
            }
        }
        uptimeText = view.findViewById(R.id.uptimeText)
        speedValue = view.findViewById(R.id.speedValue)
        pingValue = view.findViewById(R.id.pingValue)

        connectButton.setOnClickListener {
            if (hapticsEnabled) it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            animatePress(it)
            when (vm.active.value) {
                is TunnelState.Running,
                is TunnelState.Connecting,
                is TunnelState.StartingTransport,
                is TunnelState.StartingTun2Socks,
                is TunnelState.Checking,
                is TunnelState.Restoring -> vm.stop()
                is TunnelState.Idle, is TunnelState.Error, is TunnelState.Unavailable -> {
                    if (vm.selected.value == null) openManualSetup() else requestVpnAndStart()
                }
                is TunnelState.Stopping -> Unit
            }
        }

        configSelector.setOnClickListener {
            if (hapticsEnabled) it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            showConfigDropdown(it)
        }

        view.findViewById<View>(R.id.switchButton).setOnClickListener {
            animatePress(it)
            openManualSetup()
        }
        view.findViewById<View>(R.id.settingsIcon).setOnClickListener {
            animatePress(it)
            requireActivity().supportFragmentManager.beginTransaction().replace(R.id.main, SettingsFragment()).addToBackStack("settings").commit()
        }
        view.findViewById<View>(R.id.addButton).setOnClickListener {
            animatePress(it)
            qrScanner.launch(null)
        }
        val serverHandle = view.findViewById<View>(R.id.serverSwipeHandle)
        val serverGestures = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                showServerSheet()
                return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 != null && e1.y - e2.y > 70 && kotlin.math.abs(velocityY) > 250) {
                    showServerSheet()
                    return true
                }
                return false
            }
        })
        serverHandle.setOnTouchListener { _, event ->
            serverGestures.onTouchEvent(event)
        }
        view.findViewById<View>(R.id.navHome).setOnClickListener { animatePress(it) }
        view.findViewById<View>(R.id.navServers).setOnClickListener {
            animatePress(it)
            requireActivity().findViewById<ViewPager2>(R.id.view_pager)?.setCurrentItem(1, true)
        }
        view.findViewById<View>(R.id.navLogs).setOnClickListener {
            animatePress(it)
            requireActivity().findViewById<ViewPager2>(R.id.view_pager)?.setCurrentItem(2, true)
        }
        view.findViewById<View>(R.id.navProfile).setOnClickListener {
            animatePress(it)
            requireActivity().findViewById<ViewPager2>(R.id.view_pager)?.setCurrentItem(3, true)
        }

        observe()
    }

    private fun openManualSetup() {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.main, AddTunFragment.new())
                .addToBackStack("switch")
                .commit()
    }

    private fun requestVpnAndStart() {
        val intent = VpnService.prepare(requireActivity())
        if (intent != null) vpnPermission.launch(intent) else vm.startCurrent()
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.active.collect { applyState(it) } }
                launch { vm.uptimeSeconds.collect { renderUptime(it) } }
                launch { vm.pingMs.collect { value -> pingValue.text = if (vm.active.value is TunnelState.Running && value != null) "$value" else "—" } }
                launch { vm.selected.collect { renderSelected(it) } }
            }
        }
    }

    private fun renderSelected(tunnel: Tunnel?) {
        tunnelName.text = tunnel?.name ?: getString(R.string.no_configs)
        configDot.background.setTint(
            ContextCompat.getColor(
                requireContext(),
                if (tunnel != null) R.color.state_idle else R.color.state_error
            )
        )
    }

    private fun showServerSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
            background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_glass_card)
        }
        content.addView(TextView(requireContext()).apply {
            text = "Серверы"
            textSize = 22f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            setPadding(dp(4), 0, dp(4), dp(16))
        })
        val tunnels = vm.tunnels.value.map { it.tunnel }
        if (tunnels.isEmpty()) {
            content.addView(TextView(requireContext()).apply {
                text = getString(R.string.no_configs)
                textSize = 14f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                setPadding(dp(4), dp(16), dp(4), dp(24))
            })
        }
        tunnels.forEach { tunnel ->
            val row = layoutInflater.inflate(R.layout.item_server_manage, content, false)
            val isActive = vm.active.value.isActive && vm.active.value.tunnel?.id == tunnel.id
            row.findViewById<TextView>(R.id.serverName).text = tunnel.name
            val updateAvailable = ServerRelease.updateAvailable(tunnel)
            row.findViewById<View>(R.id.serverDot).backgroundTintList = if (updateAvailable) {
                ColorStateList.valueOf(android.graphics.Color.parseColor("#E5B642"))
            } else null
            row.findViewById<TextView>(R.id.serverType).text = when {
                updateAvailable -> "Доступно обновление сервера"
                isActive -> "Используется сейчас"
                tunnel.id == vm.selectedTunnelId -> "Выбран"
                else -> tunnel.transportType
            }
            row.setOnClickListener {
                if (vm.active.value is TunnelState.Running) vm.startTunnel(tunnel) else vm.selectTunnel(tunnel)
                dialog.dismiss()
            }
            val edit = row.findViewById<ImageButton>(R.id.serverEdit)
            val delete = row.findViewById<ImageButton>(R.id.serverDelete)
            edit.alpha = if (isActive) 0.28f else 1f
            delete.alpha = if (isActive) 0.28f else 1f
            edit.setOnClickListener {
                if (isActive) {
                    Toast.makeText(requireContext(), "Сначала отключите активный сервер", Toast.LENGTH_SHORT).show()
                } else {
                    dialog.dismiss()
                    requireActivity().supportFragmentManager.beginTransaction().replace(R.id.main, AddTunFragment.edit(tunnel)).addToBackStack("server_edit").commit()
                }
            }
            delete.setOnClickListener {
                if (isActive) {
                    Toast.makeText(requireContext(), "Активный сервер нельзя удалить", Toast.LENGTH_SHORT).show()
                } else {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.delete_config_title)
                        .setMessage(tunnel.name)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.action_delete) { _, _ -> vm.removeTunnel(tunnel); dialog.dismiss() }
                        .show()
                }
            }
            content.addView(row)
        }
        content.addView(TextView(requireContext()).apply {
            text = "+  Добавить вручную"
            gravity = android.view.Gravity.CENTER
            textSize = 14f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            background = ContextCompat.getDrawable(requireContext(), R.drawable.glass_action)
            setOnClickListener { dialog.dismiss(); openManualSetup() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(6) })
        dialog.setContentView(content)
        dialog.show()
    }

    

    private fun showConfigDropdown(anchor: View) {
        val tunnels = vm.tunnels.value.map { it.tunnel }
        if (tunnels.isEmpty()) {
            Toast.makeText(requireContext(), R.string.no_configs, Toast.LENGTH_SHORT).show()
            return
        }

        val inflater = LayoutInflater.from(requireContext())
        val content = inflater.inflate(R.layout.dropdown_configs, null)
        val items = content.findViewById<LinearLayout>(R.id.dropdown_items)
        val selectedId = vm.selectedTunnelId

        for (tunnel in tunnels) {
            val row = inflater.inflate(R.layout.item_dropdown_config, items, false)
            row.findViewById<TextView>(R.id.item_name).text = tunnel.name
            val check = row.findViewById<ImageView>(R.id.item_check)
            check.isVisible = tunnel.id == selectedId

            row.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                if (vm.active.value is TunnelState.Running) vm.startTunnel(tunnel) else vm.selectTunnel(tunnel)
                popup?.dismiss()
            }

            row.setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                showItemContextMenu(it, tunnel)
                true
            }

            items.addView(row)
        }

        val pw = PopupWindow(
            content,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = 12f
            isOutsideTouchable = true
            isFocusable = true
            setBackgroundDrawable(
                ContextCompat.getDrawable(requireContext(), R.drawable.bg_dropdown_menu)
            )
        }

        popup = pw

        
        content.alpha = 0f
        content.translationY = -12f
        content.scaleY = 0.95f

        pw.showAsDropDown(anchor, 0, 8)

        content.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleY(1f)
            .setDuration(180)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()

        
        chevron.animate().rotation(180f).setDuration(180).start()
        pw.setOnDismissListener {
            chevron.animate().rotation(0f).setDuration(180).start()
            popup = null
        }
    }

    

    private fun showItemContextMenu(anchor: View, tunnel: Tunnel) {
        val inflater = LayoutInflater.from(requireContext())
        val menuView = inflater.inflate(R.layout.popup_item_menu, null)

        val menu = PopupWindow(
            menuView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = 12f
            isOutsideTouchable = true
            isFocusable = true
            setBackgroundDrawable(
                ContextCompat.getDrawable(requireContext(), R.drawable.bg_menu_popup)
            )
        }

        menuView.findViewById<View>(R.id.menu_edit).setOnClickListener {
            if (vm.active.value.isActive && vm.active.value.tunnel?.id == tunnel.id) {
                Toast.makeText(requireContext(), "Сначала отключите активный сервер", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            menu.dismiss()
            popup?.dismiss()
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.main, AddTunFragment.edit(tunnel))
                .addToBackStack("edit")
                .commit()
        }

        menuView.findViewById<View>(R.id.menu_delete).setOnClickListener {
            menu.dismiss()
            confirmDelete(tunnel)
        }

        menuView.alpha = 0f
        menuView.translationY = -8f
        menuView.scaleX = 0.96f
        menuView.scaleY = 0.96f

        menu.showAsDropDown(anchor, 0, 4)

        menuView.animate()
            .alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(160)
            .setInterpolator(OvershootInterpolator(1.1f))
            .start()
    }

    private fun confirmDelete(tunnel: Tunnel) {
        val active = vm.active.value
        if (active.isActive && active.tunnel == tunnel) {
            Toast.makeText(requireContext(), R.string.cannot_delete_active, Toast.LENGTH_SHORT).show()
            return
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_config_title)
            .setMessage(R.string.delete_config_msg)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                vm.removeTunnel(tunnel)
                popup?.dismiss()
                Toast.makeText(requireContext(), R.string.config_deleted, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun applyState(state: TunnelState) {
        if (state == currentVisualState) return
        currentVisualState = state

        val color = state.color
        aurora.setStateColor(color)

        when (state) {
            is TunnelState.Idle -> {
                stopMetrics()
                loopBackgroundVideo()
                statusText.text = getString(R.string.tap_to_connect)
                headerStatus.text = getString(R.string.tap_to_connect)
                connectLabel.text = getString(R.string.tap_to_connect)
                statusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                crossFadeStatus()
                aurora.setIntensity(0.4f)
                pulseRings.stop()
                stopRotation()
                startBreath()
                animateIcon(scale = 1f, alpha = 0.92f)
                hideUptime()
            }

            is TunnelState.Connecting,
            is TunnelState.StartingTransport,
            is TunnelState.StartingTun2Socks,
            is TunnelState.Checking -> {
                stopMetrics()
                loopBackgroundVideo()
                val label = when (state) {
                    is TunnelState.Connecting -> getString(R.string.connecting)
                    is TunnelState.StartingTransport -> getString(R.string.starting_transport)
                    is TunnelState.StartingTun2Socks -> getString(R.string.starting_tsocks)
                    is TunnelState.Checking -> getString(R.string.checking_connection)
                }
                statusText.text = label
                headerStatus.text = label
                connectLabel.text = "Отменить"
                statusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                crossFadeStatus()
                aurora.setIntensity(0f)
                stopRotation()
                pulseRings.stop()
                startBreath()
                animateIcon(scale = 0.94f, alpha = 0.7f)
                hideUptime()
            }

            is TunnelState.Stopping -> {
                stopMetrics()
                loopBackgroundVideo()
                statusText.text = getString(R.string.disconnecting)
                headerStatus.text = getString(R.string.disconnecting)
                connectLabel.text = getString(R.string.disconnecting)
                statusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                crossFadeStatus()
                aurora.setIntensity(0.15f)
                pulseRings.stop()
                stopRotation()
                startBreath()
                animateIcon(scale = 0.94f, alpha = 0.65f)
                hideUptime()
            }

            is TunnelState.Restoring -> {
                stopMetrics()
                loopBackgroundVideo()
                val label = if (state.attempt == 0) "Ожидаем сеть…" else "Восстанавливаем…"
                statusText.text = label
                headerStatus.text = label
                connectLabel.text = getString(R.string.disconnect)
                statusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                crossFadeStatus()
                aurora.setIntensity(0.15f)
                pulseRings.stop()
                stopRotation()
                startBreath()
                animateIcon(scale = 0.94f, alpha = 0.7f)
                showUptime()
            }

            is TunnelState.Unavailable -> {
                stopMetrics()
                statusText.text = "Сервер не отвечает. Проверьте интернет, состояние и оплату VDS."
                headerStatus.text = "Сервер не отвечает · выберите другой"
                connectLabel.text = "Повторить"
                statusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.state_error))
                crossFadeStatus()
                aurora.setIntensity(0.7f)
                pulseRings.stop()
                stopRotation()
                stopBreath()
                animateIcon(scale = 1f, alpha = 0.9f)
                hideUptime()
            }

            is TunnelState.Running -> {
                startMetrics()
                pingValue.text = vm.pingMs.value?.let { "$it" } ?: "—"
                finishBackgroundVideoAndHold()
                statusText.text = getString(R.string.running)
                headerStatus.text = getString(R.string.running)
                connectLabel.text = getString(R.string.disconnect)
                statusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                crossFadeStatus()
                aurora.setIntensity(0f)
                stopRotation()
                pulseRings.stop()
                stopBreath()
                animateIcon(scale = 1f, alpha = 1f)
                popButton()
                showUptime()
            }

            is TunnelState.Error -> {
                stopMetrics()
                statusText.text = state.message
                headerStatus.text = getString(R.string.notify_msg_error)
                connectLabel.text = getString(R.string.tap_to_connect)
                statusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.state_error))
                crossFadeStatus()
                aurora.setIntensity(0.9f)
                pulseRings.stop()
                stopRotation()
                stopBreath()
                animateIcon(scale = 1f, alpha = 1f)
                softError()
                hideUptime()
            }
        }
    }

    private fun renderUptime(seconds: Long) {
        if (seconds <= 0L) {
            hideUptime(); return
        }
        val text = seconds.toUptimeHms()
        if (uptimeText.text != text) {
            uptimeText.text = text
            uptimeText.animate().cancel()
            uptimeText.scaleX = 0.96f; uptimeText.scaleY = 0.96f
            uptimeText.animate().scaleX(1f).scaleY(1f).setDuration(180L)
                .setInterpolator(OvershootInterpolator(1.4f)).start()
        }
    }

    private fun startMetrics() {
        if (metricsJob?.isActive != true) {
            metricsJob = viewLifecycleOwner.lifecycleScope.launch {
                var lastBytes = TrafficStats.getUidRxBytes(android.os.Process.myUid()) + TrafficStats.getUidTxBytes(android.os.Process.myUid())
                val activeSamples = ArrayDeque<Pair<Long, Long>>()
                var displayedMbps = 0.0
                while (isActive) {
                    delay(1_000L)
                    val now = SystemClock.elapsedRealtime()
                    val currentBytes = TrafficStats.getUidRxBytes(android.os.Process.myUid()) + TrafficStats.getUidTxBytes(android.os.Process.myUid())
                    val delta = (currentBytes - lastBytes).coerceAtLeast(0L)
                    if (delta >= 4_096L) activeSamples.addLast(now to delta)
                    while (activeSamples.isNotEmpty() && now - activeSamples.first().first > 60_000L) activeSamples.removeFirst()
                    if (activeSamples.isNotEmpty()) {
                        val activeMbps = activeSamples.sumOf { it.second } * 8.0 / activeSamples.size / 1_000_000.0
                        displayedMbps = if (displayedMbps == 0.0) activeMbps else displayedMbps * 0.7 + activeMbps * 0.3
                        speedValue.text = String.format(Locale.US, "%.1f", displayedMbps.coerceIn(0.0, 999.9))
                    }
                    lastBytes = currentBytes
                }
            }
        }
    }

    private fun stopMetrics() {
        metricsJob?.cancel()
        metricsJob = null
        if (::speedValue.isInitialized) speedValue.text = "—"
        if (::pingValue.isInitialized) pingValue.text = "—"
    }

    private fun loopBackgroundVideo() {
        if (!videoEnabled) return
        transitionVideo.visibility = View.VISIBLE
        transitionPlayer?.isLooping = !energySaving
        if (energySaving && transitionVideo.currentPosition > 0) return
        if (!transitionVideo.isPlaying) {
            restartBackgroundVideo()
        }
    }

    private fun restartBackgroundVideo() {
        transitionVideo.animate().cancel()
        if (!motionEnabled) {
            transitionVideo.alpha = videoAlpha
            transitionVideo.seekTo(0)
            transitionVideo.start()
            return
        }
        transitionVideo.animate().alpha(0f).setDuration(110L).withEndAction {
            transitionVideo.seekTo(0)
            transitionVideo.start()
            transitionVideo.animate().alpha(videoAlpha).setDuration(190L).start()
        }.start()
    }

    private fun finishBackgroundVideoAndHold() {
        if (!videoEnabled) return
        transitionVideo.visibility = View.VISIBLE
        transitionVideo.alpha = videoAlpha
        transitionPlayer?.isLooping = false
        if (!transitionVideo.isPlaying) transitionVideo.start()
    }

    private fun crossFadeStatus() {
        statusText.animate().cancel()
        statusText.alpha = 0f
        statusText.translationY = 6f
        statusText.animate().alpha(1f).translationY(0f).setDuration(300L)
            .setInterpolator(DecelerateInterpolator()).start()
        headerStatus.animate().cancel()
        headerStatus.alpha = 0f
        headerStatus.animate().alpha(1f).setDuration(300L).start()
    }

    private fun animatePress(view: View) {
        if (!motionEnabled) return
        view.animate().cancel()
        view.animate().scaleX(0.975f).scaleY(0.975f).setDuration(90L).withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(180L)
                .setInterpolator(DecelerateInterpolator()).start()
        }.start()
    }

    private fun animateIcon(scale: Float, alpha: Float) {
        powerIcon.animate().cancel()
        powerIcon.animate().scaleX(scale).scaleY(scale).alpha(alpha)
            .setDuration(320L).setInterpolator(AccelerateDecelerateInterpolator()).start()
    }

    private fun popButton() {
        connectButton.animate().cancel()
        connectButton.alpha = 0.72f
        connectButton.scaleX = 0.98f; connectButton.scaleY = 0.98f
        connectButton.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(360L)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    private fun softError() {
        connectButton.animate().cancel()
        connectButton.animate().alpha(0.55f).setDuration(120L).withEndAction {
            connectButton.animate().alpha(1f).setDuration(260L).start()
        }.start()
    }

    private fun shake() {
        val props = PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f, -14f, 14f, -10f, 10f, -4f, 4f, 0f)
        ObjectAnimator.ofPropertyValuesHolder(connectButton, props).apply {
            duration = 520L
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun startRotation() {
        if (rotationAnim?.isRunning == true) return
        rotationAnim = ObjectAnimator.ofFloat(ringOuter, View.ROTATION, 0f, 360f).apply {
            duration = 4200L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun stopRotation() {
        rotationAnim?.cancel()
        rotationAnim = null
        ringOuter.rotation = 0f
    }

    private fun startBreath() {
        if (breathAnim?.isRunning == true) return
        breathAnim = ObjectAnimator.ofPropertyValuesHolder(
            ringOuter,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.02f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.02f),
        ).apply {
            duration = 2400L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopBreath() {
        breathAnim?.cancel()
        breathAnim = null
        ringOuter.scaleX = 1f
        ringOuter.scaleY = 1f
    }

    private fun showUptime() {
        if (uptimeText.alpha > 0.05f) return
        uptimeText.translationY = 16f
        uptimeText.animate().alpha(1f).translationY(0f)
            .setDuration(500L).setInterpolator(OvershootInterpolator(1.2f)).start()
    }

    private fun hideUptime() {
        if (uptimeText.alpha < 0.05f) return
        uptimeText.animate().alpha(0f).setDuration(200L).start()
    }

    override fun onDestroyView() {
        stopMetrics()
        transitionVideo.stopPlayback()
        transitionPlayer = null
        rotationAnim?.cancel()
        breathAnim?.cancel()
        pulseRings.stop()
        popup?.dismiss()
        popup = null
        super.onDestroyView()
    }

    override fun onNewEvent(ev: AppEvent) = Unit

    companion object {
        fun new() = TunnelsFragment()
    }
}
