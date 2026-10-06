package com.phonesync.speaker

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.*
import java.net.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val port = 45454
    private val running = AtomicBoolean(false)
    private lateinit var status: TextView
    private lateinit var hostIp: TextView
    private lateinit var ipInput: EditText
    private lateinit var updateStatus: TextView
    private lateinit var updateBtn: Button
    private var server: ServerSocket? = null
    private var socket: Socket? = null
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var projection: MediaProjection? = null
    private val projectionRequest = 501

    // CHANGE THIS ONCE to the public HTTPS URL where update.json is hosted.
    // Example: https://your-domain.com/phonesync/update.json
    private val updateManifestUrl = "https://YOUR-DOMAIN.example/phonesync/update.json"
    private var availableApkUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_main)
        status = findViewById(R.id.status); hostIp = findViewById(R.id.hostIp); ipInput = findViewById(R.id.ipInput)
        updateStatus = findViewById(R.id.updateStatus); updateBtn = findViewById(R.id.updateBtn)
        findViewById<Button>(R.id.hostBtn).setOnClickListener { requestHost() }
        findViewById<Button>(R.id.speakerBtn).setOnClickListener { startSpeaker(ipInput.text.toString().trim()) }
        findViewById<Button>(R.id.stopBtn).setOnClickListener { stopAll("Stopped") }
        updateBtn.setOnClickListener { if (availableApkUrl == null) checkForUpdate() else downloadAndInstall(availableApkUrl!!) }
        hostIp.text = "Host IP: ${localIpv4() ?: "connect Wi‑Fi/hotspot first"}  •  Port $port"
        updateStatus.text = "Current version: v${packageManager.getPackageInfo(packageName, 0).versionName}"
    }

    private fun checkForUpdate() {
        if (updateManifestUrl.contains("YOUR-DOMAIN")) {
            updateStatus.text = "Update server not configured yet. Set the update.json HTTPS address in MainActivity.kt."
            return
        }
        updateBtn.isEnabled = false; updateStatus.text = "Checking for update…"
        thread(name="update-check") {
            try {
                val conn = URL(updateManifestUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000; conn.readTimeout = 8000; conn.useCaches = false
                val json = conn.inputStream.bufferedReader().use { it.readText() }
                val obj = JSONObject(json)
                val remoteCode = obj.getInt("versionCode")
                val remoteName = obj.optString("versionName", remoteCode.toString())
                val apkUrl = obj.getString("apkUrl")
                val currentCode = packageManager.getPackageInfo(packageName, 0).longVersionCode
                runOnUiThread {
                    updateBtn.isEnabled = true
                    if (remoteCode > currentCode) {
                        availableApkUrl = apkUrl
                        updateStatus.text = "Update available: v$remoteName"
                        updateBtn.text = "UPDATE AVAILABLE — DOWNLOAD"
                    } else {
                        availableApkUrl = null
                        updateStatus.text = "You already have the latest version."
                        updateBtn.text = "CHECK FOR UPDATE"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { updateBtn.isEnabled = true; updateStatus.text = "Update check failed: ${e.message}" }
            }
        }
    }

    private fun downloadAndInstall(url: String) {
        updateBtn.isEnabled = false; updateStatus.text = "Downloading update…"
        thread(name="update-download") {
            try {
                val dir = File(cacheDir, "updates").apply { mkdirs() }
                val apk = File(dir, "PhoneSyncSpeaker-update.apk")
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000; conn.readTimeout = 30000; conn.instanceFollowRedirects = true
                conn.inputStream.use { input -> FileOutputStream(apk).use { output -> input.copyTo(output) } }
                runOnUiThread { updateBtn.isEnabled = true; updateStatus.text = "Download complete. Android will ask you to approve the update." }
                installApk(apk)
            } catch (e: Exception) {
                runOnUiThread { updateBtn.isEnabled = true; updateStatus.text = "Download failed: ${e.message}" }
            }
        }
    }

    private fun installApk(apk: File) {
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Allow 'Install unknown apps' for Phone Sync Speaker, then tap Update again.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
        startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun requestHost() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 77); return
        }
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), projectionRequest)
    }

    override fun onRequestPermissionsResult(r: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(r,p,g); if (r == 77 && g.firstOrNull() == PackageManager.PERMISSION_GRANTED) requestHost()
    }

    @Deprecated("MediaProjection result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if (requestCode != projectionRequest || resultCode != RESULT_OK || data == null) { setStatus("Host permission cancelled"); return }
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(resultCode, data); startHost(projection!!)
    }

    private fun startHost(mp: MediaProjection) {
        stopAll(null); running.set(true); hostIp.text = "Host IP: ${localIpv4() ?: "unknown"}  •  Port $port"; setStatus("HOST: waiting for Speaker phone…")
        thread(name="host-server") {
            try {
                server = ServerSocket(port); socket = server!!.accept(); socket!!.tcpNoDelay = true; setStatus("SPEAKER CONNECTED — capturing Android playback…")
                val config = AudioPlaybackCaptureConfiguration.Builder(mp).addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME).addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
                val min = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                recorder = AudioRecord.Builder().setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()).setBufferSizeInBytes(maxOf(min * 4, 32768)).setAudioPlaybackCaptureConfig(config).build()
                val out = BufferedOutputStream(socket!!.getOutputStream(), 65536); val buf = ByteArray(3840); recorder!!.startRecording()
                while (running.get()) { val n = recorder!!.read(buf,0,buf.size); if (n > 0) { out.write(buf,0,n); out.flush() } }
            } catch (e: Exception) { if (running.get()) setStatus("Host error: ${e.message}") } finally { stopAll(null) }
        }
    }

    private fun startSpeaker(ip: String) {
        if (ip.isBlank()) { setStatus("Enter the Host IP shown on Phone 1."); return }
        stopAll(null); running.set(true); setStatus("SPEAKER: connecting to $ip…")
        thread(name="speaker-client") {
            try {
                socket = Socket(); socket!!.connect(InetSocketAddress(ip, port), 6000); socket!!.tcpNoDelay = true
                val min = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                player = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()).setBufferSizeInBytes(maxOf(min * 4, 32768)).setTransferMode(AudioTrack.MODE_STREAM).build()
                val input = BufferedInputStream(socket!!.getInputStream(),65536); val buf = ByteArray(3840); player!!.play(); setStatus("CONNECTED 🔊 — this phone is now the extra speaker")
                while (running.get()) { val n=input.read(buf); if(n<0) break; if(n>0) player!!.write(buf,0,n,AudioTrack.WRITE_BLOCKING) }
            } catch(e:Exception) { if(running.get()) setStatus("Speaker error: ${e.message}") } finally { stopAll(null) }
        }
    }

    @Synchronized private fun stopAll(message:String?) {
        running.set(false)
        try { recorder?.stop() } catch(_:Exception){}; try { recorder?.release() } catch(_:Exception){}; recorder=null
        try { player?.stop() } catch(_:Exception){}; try { player?.release() } catch(_:Exception){}; player=null
        try { socket?.close() } catch(_:Exception){}; socket=null; try { server?.close() } catch(_:Exception){}; server=null
        try { projection?.stop() } catch(_:Exception){}; projection=null
        if(message!=null) setStatus(message)
    }

    private fun setStatus(s:String)=runOnUiThread { status.text=s }
    private fun localIpv4():String? = try { NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }?.hostAddress } catch(_:Exception){ null }
    override fun onDestroy(){ stopAll(null); super.onDestroy() }
}
