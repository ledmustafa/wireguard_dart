package network.mysterium.wireguard_dart


import androidx.annotation.NonNull

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.PluginRegistry

import android.app.Activity
import io.flutter.embedding.android.FlutterActivity
import android.content.Intent
import android.content.Context
import android.util.Log
import com.beust.klaxon.Klaxon
import com.wireguard.android.backend.*
//import com.wireguard.android.util.ModuleLoader
import com.wireguard.android.util.RootShell
import com.wireguard.android.util.ToolsInstaller
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.*
import java.util.*


import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream

/** WireguardDartPlugin */
class WireguardDartPlugin: FlutterPlugin, MethodCallHandler ,ActivityAware,PluginRegistry.ActivityResultListener {
  /// The MethodChannel that will the communication between Flutter and native Android
  ///
  /// This local reference serves to register the plugin with the Flutter Engine and unregister it
  /// when the Flutter Engine is detached from the Activity
    private lateinit var channel : MethodChannel
    private lateinit var tunnelName:String
    private val permissionRequestCode = 10014
    private val channelName = "wachu985/wireguard-flutter"
    private val futureBackend = CompletableDeferred<Backend>()
    private val scope = CoroutineScope(Job() + Dispatchers.Main.immediate)
    private var backend: Backend? = null
    private lateinit var rootShell: RootShell
    private lateinit var toolsInstaller: ToolsInstaller
    private var havePermission = false
    private lateinit var context:Context
    private var activity:Activity? = null
    private  var config :com.wireguard.config.Config? = null
    private  var tunnel: WireguardTunnel? = null


  companion object {
      const val TAG = "MainActivity"
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean{
      //super.onActivityResult(requestCode, resultCode, data)
      havePermission = (requestCode == permissionRequestCode) && (resultCode == Activity.RESULT_OK)
      return havePermission

    }

    override fun onAttachedToActivity(activityPluginBinding: ActivityPluginBinding) {
      Log.d(TAG, "Entre 1")
      this.activity = activityPluginBinding.activity as FlutterActivity
    }

    override fun onDetachedFromActivityForConfigChanges() {
      this.activity = null;
    }

    override fun onReattachedToActivityForConfigChanges(activityPluginBinding: ActivityPluginBinding) {
      this.activity = activityPluginBinding.activity as FlutterActivity
    }

    override fun onDetachedFromActivity() {
      this.activity = null;
    }

  override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    channel = MethodChannel(flutterPluginBinding.binaryMessenger, "wireguard_dart")
    context = flutterPluginBinding.applicationContext
    rootShell = RootShell(context)
    toolsInstaller = ToolsInstaller(context, rootShell)

    scope.launch(Dispatchers.IO) {
      try {
        backend = createBackend()
        Log.e(TAG, "Entre 1")
        futureBackend.complete(backend!!)
        checkPermission()
      } catch (e: Throwable) {
        Log.e(TAG, Log.getStackTraceString(e))
      }
    }
    channel.setMethodCallHandler(this)
  }

  private fun createBackend(): Backend {
    var backend: Backend? = null
    if (backend == null) {
      backend = GoBackend(context)
    }
    return backend
  }

  private fun flutterSuccess(result: MethodChannel.Result, o: Any) {
    scope.launch(Dispatchers.Main) {
      result.success(o)
    }
  }

  private fun flutterError(result: MethodChannel.Result, error: String) {
    scope.launch(Dispatchers.Main) {
      result.error(error, null, null)
    }
  }

  private fun flutterNotImplemented(result: MethodChannel.Result) {
    scope.launch(Dispatchers.Main) {
      result.notImplemented()
    }
  }

  override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
    when (call.method) {
      "generateKeyPair" -> generateKeyPair(result)
      "setupTunnel" -> setupTunnel(call.argument<String>("bundleId").toString(),result)
      "connect" -> connect(call.argument<String>("cfg").toString(), result)
      "disconnect"-> disconnect(result)
      "getStats" -> handleGetStats(call.arguments, result)
      else -> flutterNotImplemented(result)
    }
  }

  private fun handleGetStats(arguments: Any?, result: MethodChannel.Result) {
    val tunnelName = arguments?.toString()
    if (tunnelName == null || tunnelName.isEmpty()) {
      flutterError(result, "Provide tunnel name to get statistics")
      return
    }

    scope.launch(Dispatchers.IO) {

      try {
        val stats = futureBackend.await().getStatistics(tunnel(tunnelName))

        flutterSuccess(result, Klaxon().toJsonString(
          Stats(stats.totalRx(), stats.totalTx())
        ))

      } catch (e: BackendException) {
        Log.e(TAG, "handleGetStats - BackendException - ERROR - ${e.reason}")
        flutterError(result, e.reason.toString())
      } catch (e: Throwable) {
        Log.e(TAG, "handleGetStats - Can't get stats: $e")
        flutterError(result, e.message.toString())
      }
    }
  }

  private fun disconnect( result: MethodChannel.Result) {

    scope.launch(Dispatchers.IO) {
      try {
        if (futureBackend.await().runningTunnelNames.isEmpty()) {
          throw  Exception("Tunnel is not running")
        }

        futureBackend.await().setState(
          tunnel(tunnelName) { state ->
            scope.launch(Dispatchers.Main) {
              Log.i(TAG, "onStateChange - $state")
              channel?.invokeMethod(
                "onStateChange",
                state == Tunnel.State.UP
              )
            }
          },
          Tunnel.State.DOWN,
          config
        )
        Log.i(TAG, "disconnect - success!")
        flutterSuccess(result, "")
      } catch (e: BackendException) {
        Log.e(TAG, "disconnect - BackendException - ERROR - ${e.reason}")
        flutterError(result, e.reason.toString())
      } catch (e: Throwable) {
        Log.e(TAG, "handleSetState - Can't disconnect from tunnel: $e, ${Log.getStackTraceString(e)}")
        flutterError(result, e.message.toString())
      }
    }
  }

  private fun connect(cfg: String, result: MethodChannel.Result) {

    scope.launch(Dispatchers.IO) {
      try {
        val inputStream = ByteArrayInputStream(cfg.toByteArray())
        config =  com.wireguard.config.Config.parse(inputStream)
        futureBackend.await().setState(
          tunnel(tunnelName) { state ->
            scope.launch(Dispatchers.Main) {
              Log.i(TAG, "onStateChange - $state")
              channel?.invokeMethod(
                "onStateChange",
                state == Tunnel.State.UP
              )
            }
          },
           Tunnel.State.UP,
          config
        )
        Log.i(TAG, "connect - success!")
        flutterSuccess(result, "")
      } catch (e: BackendException) {
        Log.e(TAG, "connect - BackendException - ERROR - ${e.reason}")
        flutterError(result, e.reason.toString())
      } catch (e: Throwable) {
        Log.e(TAG, "connect - Can't connect to tunnel: $e, ${Log.getStackTraceString(e)}")
        flutterError(result, e.message.toString())
      }
    }
  }

  private  fun setupTunnel(bundleId: String,result:Result)
  {
    scope.launch(Dispatchers.IO) {
      if (Tunnel.isNameInvalid(bundleId))
      {
        result.error("400","Invalid Name",IllegalArgumentException() )
      }
      tunnelName = bundleId;
      checkPermission()
      result.success(null)
      return@launch
    }
  }

  private fun checkPermission() {
    val intent = GoBackend.VpnService.prepare(this.activity)
    if (intent != null) {
      havePermission = false
      this.activity?.startActivityForResult(intent, permissionRequestCode)
    } else {
      havePermission = true
    }
  }

  private fun generateKeyPair(result: Result)
  {
    val keyPair = KeyPair()
    val privateKey = keyPair.privateKey.toBase64()
    val publicKey = KeyPair(Key.fromBase64(privateKey)).publicKey.toBase64()
    val map: HashMap<String, String> = hashMapOf("privateKey" to privateKey, "publicKey" to publicKey)
    result.success(map)
    return
  }

  override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    channel.setMethodCallHandler(null)
  }

  private fun tunnel(name: String, callback: StateChangeCallback? = null): WireguardTunnel {
    if(tunnel == null)
    {
      tunnel = WireguardTunnel(name,callback)
    }
    return tunnel as WireguardTunnel
  }
}

typealias StateChangeCallback = (Tunnel.State) -> Unit

class WireguardTunnel(private val name: String,
                      private val onStateChanged: StateChangeCallback? = null) : Tunnel {

  override fun getName() = name

  override fun onStateChange(newState: Tunnel.State) {
    onStateChanged?.invoke(newState)
  }

}

class Stats(
  val totalDownload: Long,
  val totalUpload: Long,
)


