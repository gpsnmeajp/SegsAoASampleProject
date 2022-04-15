/**
 * SegsAoASampleProject by gpsnmeajp v0.01
 * 
 * These codes are licensed under CC0.
 * http://creativecommons.org/publicdomain/zero/1.0/deed.ja
 */
package com.example.aoahello

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import android.widget.Button
import android.widget.TextView
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.concurrent.schedule

/**
 * Android Open Accessoriesの仕組みで通信するための一通りの処理を実装したクラス
 * 初期化と受信時コールバックの設定, open, close, writeの4つを理解していれば使用可能
 *
 * 注意: USBホスト側からは定期的にbulkでデータを送信する必要がある。でないと通信スレッドがハングアップする。(これはAndroid APIの制約)
 */
class UsbPeripheral(_context:Context ,_usbManager: UsbManager, _handler: Handler, _onReceived: (ByteArray)->Unit) {
    /**
     * Permissionリクエスト時のIntent (ただし接続時の処理の関係でタイマーポーリングしているため事実上使用していない)
     */
    private val actionUsbPermission = "com.android.UsbPeripheral.USB_PERMISSION";

    /**
     * タイマーポーリング間隔。接続・切断の処理はこのミリ秒間隔で行う
     */
    private val checkCycleMs:Long= 100;

    /**
     * ログ用のタグ
     */
    private val tag = "UsbPeripheral"

    /**
     * UsbManager(Activityの持っているUSB管理インスタンス)
     */
    private val usbManager: UsbManager = _usbManager

    /**
     * UIThreadに処理を委譲するためのHnadler
     */
    private val handler:Handler = _handler

    /**
     * Activityのcontext
     */
    private val context:Context = _context

    /**
     * 受信時コールバック処理
     */
    private val onReceived:(ByteArray)->Unit = _onReceived

    /**
     * 送信データバッファ(通信スレッドで処理します)
     */
    private val writeQueue: ConcurrentLinkedDeque<ByteArray> = ConcurrentLinkedDeque()

    /**
     * 接続・切断監視タイマー
     */
    private var timer: Timer? = null

    /**
     * 通信スレッド
     */
    private var thread:Thread? = null

    /**
     * パーミッションリクエストを1動作につき1回だけにするためのフラグ
     */
    private var isRequested = false

    /**
     * 通信対象のアクセサリ
     */
    private var accessory: UsbAccessory? = null

    /**
     * 通信用ファイルディスクリプタ
     */
    private var parcelFileDescriptor: ParcelFileDescriptor? = null

    /**
     * 通信入力用ストリーム
     */
    private var inputStream: FileInputStream? = null

    /**
     * 通信出力用ストリーム
     */
    private var outputStream: FileOutputStream? = null

    /**
     * 接続・切断タイマーハンドラ
     * ここではアクセサリの接続・切断・パーミッションチェック・パーミッション要求を行います。
     */
    private fun onTimer(){
        //アクセサリが検出されているかチェック(仕様上1個しか存在し得ないため、firstOrNullで取得)
        accessory = usbManager.accessoryList?.firstOrNull()

        //アクセサリが検出できなくなった AND アクセサリを開いている
        if (!isAccessoryConnected() && isAccessoryOpened()) {
            //アクセサリを閉じる処理を行う
            closeAccessory()
            return
        }

        //アクセサリが検出している AND アクセサリを開いていいない
        if(isAccessoryConnected() && !isAccessoryOpened()) {
            //アクセサリへの接続許可があるかチェック
            if (hasAccessoryPermission()) {
                //接続許可がある(接続インテントで起動している or ユーザーが許可した)場合、アクセサリを開く
                openAccessory()
            } else {
                //接続許可がない
                if (!isRequested) {
                    isRequested = true;
                    //1回だけ接続許可を求めるダイアログを出す
                    requestAccessoryPermission()
                }
            }
        }
    }

    /**
     * 通信スレッドの開始
     */
    private fun startAccessoryThread()
    {
        Log.d(tag, "startAccessoryThread")
        //Threadを準備する
        thread = Thread(){
            //Threadが中断されるまで実行し続ける
            while (!Thread.currentThread().isInterrupted) {
                try {
                    //送信バッファにデータが有る場合は、全部取り出して送信する
                    while (writeQueue.isNotEmpty() && !Thread.currentThread().isInterrupted) {
                        writeAccessory(writeQueue.pop())
                    }
                    //スレッドが中断された場合は処理を中止する
                    if (Thread.currentThread().isInterrupted) {
                        break
                    }
                    //受信をトライする(通信失敗、エラー時などはnullが帰る)
                    val result = readAccessory()
                    if (result != null) {
                        //受信データがある場合、UIスレッドでコールバック処理を呼ぶ
                        handler.post {
                            onReceived(result)
                        }
                    } else {
                        //何も受信できない or エラー状態の場合、OSに時間を譲る
                        Thread.sleep(1)
                    }
                } catch (e: InterruptedException) {
                    //スレッドが中断された場合は処理を中止する
                    Log.d(tag, "startAccessoryThread: InterruptedException")
                    break
                }
            }
        }
        //スレッドを開始する
        thread?.start()
    }

    /**
     * 通信スレッドの停止
     */
    private fun stopAccessoryThread()
    {
        Log.d(tag, "stopAccessoryThread")
        //スレッドを中断する。完了を待つとUIThreadのフリーズの原因になるため放置して開放する
        thread?.interrupt()
        thread = null
    }

    /**
     * アクセサリが検出されているか
     */
    private fun isAccessoryConnected():Boolean{
        return accessory != null
    }

    /**
     * アクセサリを開いているか
     */
    private fun isAccessoryOpened():Boolean{
        return parcelFileDescriptor != null
    }

    /**
     * 通信許可があるか
     */
    private fun hasAccessoryPermission():Boolean {
        val hasPermission = usbManager.hasPermission(accessory)
        Log.d(tag, "hasAccessoryPermission: $hasPermission")
        return hasPermission
    }

    /**
     * 通信許可を要求
     */
    private fun requestAccessoryPermission() {
        Log.d(tag, "requestAccessoryPermission")
        val permissionIntent = PendingIntent.getBroadcast(context, 0, Intent(actionUsbPermission),0)
        usbManager.requestPermission(accessory, permissionIntent)
    }

    /**
     * アクセサリを開く
     */
    private fun openAccessory(){
        Log.d(tag, "openAccessory")

        //アクセサリを開いてファイルディスクリプタを得る
        parcelFileDescriptor = usbManager.openAccessory(accessory)

        //無事開けていればストリームを取得、開けていない場合はnullとなる
        parcelFileDescriptor?.fileDescriptor?.also { fd ->
            Log.d(tag, "openAccessory: Success")
            inputStream = FileInputStream(fd)
            outputStream = FileOutputStream(fd)
        }
    }

    /**
     * アクセサリを閉じる
     */
    private fun closeAccessory(){
        Log.d(tag, "closeAccessory")

        //ファイルディスクリプタとストリームを閉じる
        inputStream?.close()
        inputStream = null

        outputStream?.close()
        outputStream = null

        parcelFileDescriptor?.close()
        parcelFileDescriptor = null
    }

    /**
     * アクセサリから読み込む
     * この処理はブロッキング処理である(Android側の制約。Availableなどは使用できない)
     */
    private fun readAccessory():ByteArray?{
        //16384 byteはAndroidの推奨バッファサイズ
        val byteArray = ByteArray(16384)
        var length:Int? = null
        try {
            //ここで受信するまでブロックが発生する
            length = inputStream?.read(byteArray)
        }catch (e:IOException){
            return null
        }
        //受信できたサイズの配列を返す
        return byteArray.copyOf(length ?: 0)
    }

    /**
     * アクセサリに送信する
     * この処理はブロッキング処理である
     */
    private fun writeAccessory(d:ByteArray){
        outputStream?.write(d)
        outputStream?.flush()
    }

    /**
     * UsbPeripheralを開く
     * onResumeで行うことを想定
     */
    fun open(){
        Log.d(tag, "open")

        //接続監視タイマーを起動する
        timer = Timer()
        timer?.schedule(checkCycleMs,checkCycleMs){
            onTimer()
        }
        //通信スレッドを開始する
        startAccessoryThread()
    }

    /**
     * UsbPeripheralを閉じる
     * onPauseで行うことを想定
     */
    fun close(){
        Log.d(tag, "close")

        //タイマーを停止
        timer?.cancel()

        //通信スレッドを停止
        stopAccessoryThread()

        //開いているアクセサリを閉じる
        closeAccessory()

        //パーミッション許可を初期化
        isRequested = false;
    }

    /**
     * UsbPeripheralで送信する
     * 送信キューに貯められ、通信スレッドが送信する
     */
    fun write(d:ByteArray){
        writeQueue.push(d)
    }
}

class MainActivity : AppCompatActivity() {
    private lateinit var usbPeripheral: UsbPeripheral
    private var i = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        //USBマネージャを取得
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        //UIスレッドハンドラを取得
        val handler = Handler(Looper.getMainLooper())

        //UsbPeripheralを初期化
        usbPeripheral = UsbPeripheral(this, usbManager, handler) {
            //受信したときのコールバック
            val data = it
            val msg = it.decodeToString()
            //文字列に変換してTextViewに表示
            findViewById<TextView>(R.id.textView).text = msg
        }

        //ボタン押されたとき
        findViewById<Button>(R.id.button_send).setOnClickListener {
            val msg = "Hello $i"
            val data = msg.encodeToByteArray()
            //ByteArrayに変換して送信
            usbPeripheral.write(data)
            i+=1
        }
    }

    override fun onResume() {
        super.onResume()
        //Resume時に処理を開始
        usbPeripheral.open()
    }

    override fun onPause() {
        super.onPause()
        //Pause時に処理を停止
        usbPeripheral.close()
    }
}
