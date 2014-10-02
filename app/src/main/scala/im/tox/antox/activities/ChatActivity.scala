package im.tox.antox.activities

import android.app.Activity
import android.app.AlertDialog
import android.content.CursorLoader
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.preference.PreferenceManager
import android.provider.MediaStore
import android.support.v4.app.Fragment
import android.support.v7.app.ActionBarActivity
import android.support.v7.app.ActionBar
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import java.io.File
import java.io.IOException
import java.util.ArrayList
import java.util.Date
import java.util.List
import java.util.Random
import java.util.concurrent.TimeUnit
import im.tox.antox.R
import im.tox.antox.adapters.ChatMessagesAdapter
import im.tox.antox.data.AntoxDB
import im.tox.antox.tox.ToxSingleton
import im.tox.antox.tox.Reactive
import im.tox.antox.tox.Methods
import im.tox.antox.utils.AntoxFriend
import im.tox.antox.utils.ChatMessages
import im.tox.antox.utils.Constants
import im.tox.antox.utils.FileDialog
import im.tox.antox.utils.FriendInfo
import im.tox.antox.utils.IconColor
import im.tox.antox.utils.Tuple
import im.tox.antox.utils.UserStatus
import im.tox.jtoxcore.ToxException
import im.tox.jtoxcore.ToxUserStatus
import rx.{Observable => JObservable}
import rx.{Observer => JObserver}
import rx.{Subscriber => JSubscriber}
import rx.{Subscription => JSubscription}
import rx.android.schedulers.AndroidSchedulers
import rx.functions.Action1
import rx.schedulers.Schedulers
import rx.subscriptions.Subscriptions
import rx.lang.scala.JavaConversions
import rx.lang.scala.Observable
import rx.lang.scala.Observer
import rx.lang.scala.Subscriber
import rx.lang.scala.Subscription
import rx.lang.scala.Subject
import rx.lang.scala.schedulers.IOScheduler
import rx.lang.scala.schedulers.AndroidMainThreadScheduler

class ChatActivity extends ActionBarActivity {
  val TAG: String = "im.tox.antox.activities.ChatActivity"
  //var ARG_CONTACT_NUMBER: String = "contact_number"
  var adapter: ChatMessagesAdapter = null
  var messageBox: EditText = null
  var isTypingBox: TextView = null
  var statusTextBox: TextView = null
  var chatListView: ListView = null
  var displayNameView: TextView = null
  var statusIconView: View = null
  var avatarActionView: View = null
  var messagesSub: JSubscription = null
  //var progressSub: Subscription
  //var activeKeySub: Subscription
  var titleSub: JSubscription = null
  //var typingSub: Subscription
  //var chatMessages: ArrayList<ChatMessages>
  var activeKey: String = null
  var scrolling: Boolean = false
  var antoxDB: AntoxDB = null
  var photoPath: String = null
  override def onCreate(savedInstanceState: Bundle) = {
    super.onCreate(savedInstanceState)
    overridePendingTransition(R.anim.slide_from_right, R.anim.fade_scale_out)
    setContentView(R.layout.activity_chat)
    val actionBar = getSupportActionBar()
    val avatarView = getLayoutInflater.inflate(R.layout.avatar_actionview, null)
    actionBar.setCustomView(avatarView)
    actionBar.setDisplayOptions(ActionBar.DISPLAY_SHOW_CUSTOM)
    val extras: Bundle = getIntent().getExtras()
    val key = extras.getString("key")
    activeKey = key
    val thisActivity = this
    Log.d(TAG, "key = " + key)
    val preferences = PreferenceManager.getDefaultSharedPreferences(this)
    adapter = new ChatMessagesAdapter(this, getCursor(), antoxDB.getMessageIds(key, preferences.getBoolean("action_messages", true)))
    displayNameView = this.findViewById(R.id.displayName).asInstanceOf[TextView]
    statusIconView = this.findViewById(R.id.icon).asInstanceOf[View]
    avatarActionView = this.findViewById(R.id.avatarActionView).asInstanceOf[View]
    avatarActionView.setOnClickListener(new View.OnClickListener() {
      override def onClick(v: View) {
        thisActivity.finish()
      }
    })
    chatListView = this.findViewById(R.id.chatMessages).asInstanceOf[ListView]
    chatListView.setTranscriptMode(AbsListView.TRANSCRIPT_MODE_NORMAL)
    chatListView.setStackFromBottom(true)
    chatListView.setAdapter(adapter)
    chatListView.setOnScrollListener(new AbsListView.OnScrollListener() {

      override def onScrollStateChanged(view: AbsListView, scrollState: Int) {
        scrolling = !(scrollState == AbsListView.OnScrollListener.SCROLL_STATE_IDLE)
      }

      override def onScroll(view: AbsListView, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {

      }

    })
    isTypingBox = this.findViewById(R.id.isTyping).asInstanceOf[TextView]
    statusTextBox = this.findViewById(R.id.chatActiveStatus).asInstanceOf[TextView]

    messageBox = this.findViewById(R.id.yourMessage).asInstanceOf[EditText]
    messageBox.addTextChangedListener(new TextWatcher() {
      override def beforeTextChanged(charSequence: CharSequence, i: Int, i2: Int, i3: Int) {
        val isTyping = (i3 > 0)
        val mFriend = ToxSingleton.getAntoxFriend(key)
        mFriend.foreach(friend => {
          if (friend.isOnline()) {
            try {
              ToxSingleton.jTox.sendIsTyping(friend.getFriendnumber(), isTyping)
            } catch {
              case te: ToxException => {
              }
              case e: Exception => {
              }
            }
          }
        })
      }

      override def onTextChanged(charSequence: CharSequence, i: Int, i2: Int, i3: Int) {
      }

      override def afterTextChanged(editable: Editable) {
      }
    })

    messageBox.setOnFocusChangeListener(new View.OnFocusChangeListener() {
      override def onFocusChange(v: View, hasFocus: Boolean) {
        //chatListView.setSelection(adapter.getCount() - 1)
      }
    })

    val b = this.findViewById(R.id.sendMessageButton)
    b.setOnClickListener(new View.OnClickListener() {
      override def onClick(v: View) {
        sendMessage()
        val mFriend = ToxSingleton.getAntoxFriend(key)
        mFriend.foreach(friend => {
          try {
            ToxSingleton.jTox.sendIsTyping(friend.getFriendnumber(), false)
          } catch {
            case te: ToxException => {
            }
            case e: Exception => {
            }
          }
        })
      }
    })

    val attachmentButton = this.findViewById(R.id.attachmentButton)


    attachmentButton.setOnClickListener(new View.OnClickListener() {

      override def onClick(v: View) {
        val builder = new AlertDialog.Builder(thisActivity)
        var items: Array[CharSequence] = null
        items = Array(getResources.getString(R.string.attachment_photo), getResources.getString(R.string.attachment_takephoto), getResources.getString(R.string.attachment_file))
        builder.setItems(items, new DialogInterface.OnClickListener() {

          override def onClick(dialogInterface: DialogInterface, i: Int) = i match {
            case 0 => 
              var intent = new Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
              startActivityForResult(intent, Constants.IMAGE_RESULT)

            case 1 => 
              var cameraIntent = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
              var image_name = "Antoxpic" + new Date().toString
              var storageDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
              var file: File = null
              try {
                file = File.createTempFile(image_name, ".jpg", storageDir)
              } catch {
                case e: IOException => e.printStackTrace()
              }
              if (file != null) {
                val imageUri = Uri.fromFile(file)
                cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, imageUri)
                photoPath = file.getAbsolutePath
              }
              startActivityForResult(cameraIntent, Constants.PHOTO_RESULT)

                case 2 => 
                  var mPath = new File(Environment.getExternalStorageDirectory + "//DIR//")
                    var fileDialog = new FileDialog(thisActivity, mPath)
                    fileDialog.addFileListener(new FileDialog.FileSelectedListener() {

                      def fileSelected(file: File) {
                        ToxSingleton.sendFileSendRequest(file.getPath, activeKey, thisActivity)
                      }
                    })
                  fileDialog.showDialog()

          }
        })
      builder.create().show()
      }
    })
  }

  override def onCreateOptionsMenu(menu: Menu): Boolean = {
    // Inflate the menu items for use in the action bar
    val inflater: MenuInflater = getMenuInflater()
    inflater.inflate(R.menu.chat_activity, menu)
    super.onCreateOptionsMenu(menu)
  }

  def setDisplayName(name: String) = {
    this.displayNameView.setText(name)
  }

  override def onResume() = {
    super.onResume()
    val thisActivity = this
    ToxSingleton.activeKeySubject.onNext(activeKey)
    Reactive.activeKey.onNext(Some(activeKey))
    Reactive.chatActive.onNext(true)
    val antoxDB = new AntoxDB(getApplicationContext())
    antoxDB.markIncomingMessagesRead(activeKey)
    ToxSingleton.clearUselessNotifications(activeKey)
    ToxSingleton.updateMessages(getApplicationContext())
    messagesSub = ToxSingleton.updatedMessagesSubject.subscribe(new Action1[Boolean]() {
      override def call(b: Boolean) {
        Log.d(TAG,"Messages updated")
        updateChat()
        antoxDB.close()
      }
    })
    titleSub = ToxSingleton.friendInfoListSubject
      .observeOn(AndroidSchedulers.mainThread())
      .subscribe(new Action1[ArrayList[FriendInfo]]() {

        override def call(fi: ArrayList[FriendInfo]) {
          val key = activeKey
          val mFriend: Option[FriendInfo] = fi
            .toArray(Array[FriendInfo]())
            .filter(f => f.friendKey == key)
            .headOption
            mFriend match {
              case Some(friend) => {
                if (friend.alias != "") {
                  thisActivity.setDisplayName(friend.alias)
                } else {
                  thisActivity.setDisplayName(friend.friendName)
                }
                thisActivity.statusIconView.setBackground(thisActivity.getResources.getDrawable(IconColor.iconDrawable(friend.isOnline, UserStatus.getToxUserStatusFromString(friend.friendStatus))))
              }
              case None => {
                thisActivity.setDisplayName("")
              }
            }
        }
      })
  }



  private def sendMessage() {
    Log.d(TAG,"sendMessage")
    if (messageBox.getText() != null && messageBox.getText().toString().length() == 0) {
      return
    }
    var msg: String = null
    if (messageBox.getText() != null ) {
      msg = messageBox.getText().toString()
    } else {
      msg = ""
    }
    val key = activeKey
    messageBox.setText("")
    Methods.sendMessage(this, key, msg, None)
  }

  def updateChat() = {
    val observable: Observable[Cursor] = Observable((observer) => {
      val cursor: Cursor = getCursor()
      observer.onNext(cursor)
      observer.onCompleted()
    })
    observable
      .subscribeOn(IOScheduler())
      .observeOn(AndroidMainThreadScheduler())
      .subscribe((cursor: Cursor) => {
        adapter.changeCursor(cursor)
        Log.d(TAG, "changing chat list cursor")
      })
      Log.d("ChatFragment", "new key: " + activeKey)
  }

  override def onActivityResult(requestCode: Int, resultCode: Int, data: Intent) {
    super.onActivityResult(requestCode, resultCode, data)
    Log.d("ChatFragment ImageResult resultCode", java.lang.Integer.toString(resultCode))
    Log.d("ChatFragment ImageResult requestCode", java.lang.Integer.toString(requestCode))
    if (requestCode == Constants.IMAGE_RESULT && resultCode == Activity.RESULT_OK) {
      val uri = data.getData
      var path: String = null
      val filePathColumn = Array(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DISPLAY_NAME)
      var filePath: String = null
      var fileName: String = null
      val loader = new CursorLoader(this, uri, filePathColumn, null, null, null)
      val cursor = loader.loadInBackground()
      if (cursor != null) {
        if (cursor.moveToFirst()) {
          val columnIndex = cursor.getColumnIndexOrThrow(filePathColumn(0))
          filePath = cursor.getString(columnIndex)
          val fileNameIndex = cursor.getColumnIndexOrThrow(filePathColumn(1))
          fileName = cursor.getString(fileNameIndex)
        }
      }
      try {
        path = filePath
      } catch {
        case e: Exception => Log.d("onActivityResult", e.toString)
      }
      if (path != null) {
        ToxSingleton.sendFileSendRequest(path, this.activeKey, this)
      }
    }
    if (requestCode == Constants.PHOTO_RESULT && resultCode == Activity.RESULT_OK) {
      if (photoPath != null) {
        ToxSingleton.sendFileSendRequest(photoPath, this.activeKey, this)
        photoPath = null
      }
    }
  }

  def getCursor():Cursor = {
    if (antoxDB == null) {
      antoxDB = new AntoxDB(this)
    }
    val preferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
    val cursor: Cursor = antoxDB.getMessageCursor(activeKey, preferences.getBoolean("action_messages", true))
    return cursor
  }

  override def onPause() = {
    super.onPause()
    Reactive.chatActive.onNext(false)
    if (isFinishing()) overridePendingTransition(R.anim.fade_scale_in, R.anim.slide_to_right);
    messagesSub.unsubscribe()
  titleSub.unsubscribe()
  }
}
