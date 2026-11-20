package com.sha.mymoney

import android.app.*
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.ContentValues
import android.provider.MediaStore
import android.os.Environment
import android.content.Intent
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.graphics.*
import android.view.*
import android.widget.*
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.*
import java.io.ByteArrayOutputStream
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

private var BG = 0xFF080E1B.toInt()
private var PANEL = 0xFF111D31.toInt()
private var GOLD = 0xFF4D9FFF.toInt()
private var MUTED = 0xFF9CB2CE.toInt()
private var COLORS = intArrayOf(0xFF4D9FFF.toInt(),0xFF37C7F4.toInt(),0xFF8B9BFF.toInt(),0xFF54D6B0.toInt(),0xFFFFC857.toInt(),0xFFB58BFF.toInt(),0xFFFF7AA8.toInt(),0xFF66D9E8.toInt(),0xFF9AD86B.toInt(),0xFFFF9A65.toInt())
private val CUSTOM_CATEGORY_ICONS=mutableMapOf<String,String>()
private fun categoryIcon(name:String):String=CUSTOM_CATEGORY_ICONS[name]?.takeIf{it.isNotBlank()}?:when(name.lowercase(Locale.ROOT)){"food","groceries","restaurant"->"🍔";"transport","travel","fuel"->"🚗";"shopping","clothes"->"🛍️";"bills","utilities","rent"->"🧾";"health","medical"->"💊";"entertainment","movies"->"🎬";"salary","income"->"💰";"education"->"📚";"home"->"🏠";"subscriptions"->"📱";"cash"->"💵";"bank"->"🏦";"savings"->"🐷";else->"✨"}

data class Tx(val id:Long,val title:String,val amount:Double,val kind:String,val category:String,val date:String,val account:String)
data class Budget(val id:Long,val category:String,val amount:Double,val period:String)
data class Account(val id:Long,val name:String,val opening:Double)
data class Bill(val id:Long,val title:String,val amount:Double,val category:String,val dueDate:String,val frequency:String,val account:String)

class Store(c:android.content.Context):SQLiteOpenHelper(c,"mymoney.db",null,5) {
 override fun onCreate(db:SQLiteDatabase) {
  db.execSQL("CREATE TABLE tx(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT,amount REAL,kind TEXT,category TEXT,date TEXT,account TEXT DEFAULT 'Cash')")
  db.execSQL("CREATE TABLE categories(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT UNIQUE,kind TEXT DEFAULT 'Both',icon TEXT DEFAULT '')")
  db.execSQL("CREATE TABLE accounts(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT UNIQUE,opening REAL DEFAULT 0)")
  db.execSQL("CREATE TABLE budgets(id INTEGER PRIMARY KEY AUTOINCREMENT,category TEXT UNIQUE,amount REAL,period TEXT DEFAULT 'Month')")
  db.execSQL("CREATE TABLE bills(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT,amount REAL,category TEXT,dueDate TEXT,frequency TEXT,account TEXT)")
  seed(db)
 }
 private fun seed(db:SQLiteDatabase) {
  listOf("Food","Transport","Shopping","Bills","Health","Entertainment","Salary","Other").forEach{db.execSQL("INSERT OR IGNORE INTO categories(name,kind) VALUES(?,?)",arrayOf(it,if(it=="Salary")"Income" else "Expense"))}
  db.execSQL("INSERT OR IGNORE INTO accounts(name,opening) VALUES('Cash',0)")
 }
 override fun onUpgrade(db:SQLiteDatabase,old:Int,now:Int) {
  if(old<2){try{db.execSQL("ALTER TABLE tx ADD COLUMN account TEXT DEFAULT 'Cash'")}catch(_:Exception){}}
  if(old<3){db.execSQL("CREATE TABLE IF NOT EXISTS categories(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT UNIQUE,kind TEXT DEFAULT 'Both')");db.execSQL("CREATE TABLE IF NOT EXISTS accounts(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT UNIQUE,opening REAL DEFAULT 0)");db.execSQL("CREATE TABLE IF NOT EXISTS budgets(id INTEGER PRIMARY KEY AUTOINCREMENT,category TEXT UNIQUE,amount REAL,period TEXT DEFAULT 'Month')");seed(db)}
  if(old<4){db.execSQL("CREATE TABLE IF NOT EXISTS bills(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT,amount REAL,category TEXT,dueDate TEXT,frequency TEXT,account TEXT)")}
  if(old<5){try{db.execSQL("ALTER TABLE categories ADD COLUMN icon TEXT DEFAULT ''")}catch(_:Exception){}}
 }
 fun all():List<Tx>{val a=mutableListOf<Tx>();readableDatabase.rawQuery("SELECT id,title,amount,kind,category,date,COALESCE(account,'Cash') FROM tx ORDER BY date DESC,id DESC",null).use{while(it.moveToNext())a+=Tx(it.getLong(0),it.getString(1),it.getDouble(2),it.getString(3),it.getString(4),it.getString(5),it.getString(6))};return a}
 fun save(t:Tx){if(t.id==0L)writableDatabase.execSQL("INSERT INTO tx(title,amount,kind,category,date,account) VALUES(?,?,?,?,?,?)",arrayOf(t.title,t.amount,t.kind,t.category,t.date,t.account)) else writableDatabase.execSQL("UPDATE tx SET title=?,amount=?,kind=?,category=?,date=?,account=? WHERE id=?",arrayOf(t.title,t.amount,t.kind,t.category,t.date,t.account,t.id))}
 fun remove(id:Long){writableDatabase.delete("tx","id=?",arrayOf(id.toString()))}
 fun categories():List<String>{val a=mutableListOf<String>();CUSTOM_CATEGORY_ICONS.clear();readableDatabase.rawQuery("SELECT name,COALESCE(icon,'') FROM categories ORDER BY name",null).use{while(it.moveToNext()){a+=it.getString(0);val icon=it.getString(1);if(icon.isNotBlank())CUSTOM_CATEGORY_ICONS[it.getString(0)]=icon}};return a}
 fun addCategory(n:String,icon:String=""){writableDatabase.execSQL("INSERT OR IGNORE INTO categories(name,kind,icon) VALUES(?,'Both',?)",arrayOf(n,icon));if(icon.isNotBlank())CUSTOM_CATEGORY_ICONS[n]=icon}
 fun deleteCategory(n:String){writableDatabase.delete("categories","name=?",arrayOf(n))}
 fun accounts():List<Account>{val a=mutableListOf<Account>();readableDatabase.rawQuery("SELECT id,name,opening FROM accounts ORDER BY name",null).use{while(it.moveToNext())a+=Account(it.getLong(0),it.getString(1),it.getDouble(2))};return a}
 fun saveAccount(id:Long,n:String,v:Double){if(id==0L)writableDatabase.execSQL("INSERT OR IGNORE INTO accounts(name,opening) VALUES(?,?)",arrayOf(n,v)) else {val db=writableDatabase;val oldName=readableDatabase.rawQuery("SELECT name FROM accounts WHERE id=?",arrayOf(id.toString())).use{if(it.moveToFirst())it.getString(0) else null};db.beginTransaction();try{db.execSQL("UPDATE accounts SET name=?,opening=? WHERE id=?",arrayOf(n,v,id));if(oldName!=null&&oldName!=n){db.execSQL("UPDATE tx SET account=? WHERE account=?",arrayOf(n,oldName));db.execSQL("UPDATE bills SET account=? WHERE account=?",arrayOf(n,oldName))};db.setTransactionSuccessful()}finally{db.endTransaction()}}}
 fun deleteAccount(id:Long){writableDatabase.delete("accounts","id=?",arrayOf(id.toString()))}
 fun budgets():List<Budget>{val a=mutableListOf<Budget>();readableDatabase.rawQuery("SELECT id,category,amount,period FROM budgets ORDER BY category",null).use{while(it.moveToNext())a+=Budget(it.getLong(0),it.getString(1),it.getDouble(2),it.getString(3))};return a}
 fun saveBudget(id:Long,c:String,v:Double,p:String){writableDatabase.execSQL("INSERT OR REPLACE INTO budgets(id,category,amount,period) VALUES(COALESCE((SELECT id FROM budgets WHERE category=?),?),?,?,?)",arrayOf(c,if(id==0L)null else id,c,v,p))}
 fun deleteBudget(id:Long){writableDatabase.delete("budgets","id=?",arrayOf(id.toString()))}
 fun bills():List<Bill>{val a=mutableListOf<Bill>();readableDatabase.rawQuery("SELECT id,title,amount,category,dueDate,frequency,account FROM bills ORDER BY dueDate,id",null).use{while(it.moveToNext())a+=Bill(it.getLong(0),it.getString(1),it.getDouble(2),it.getString(3),it.getString(4),it.getString(5),it.getString(6))};return a}
 fun saveBill(b:Bill){if(b.id==0L)writableDatabase.execSQL("INSERT INTO bills(title,amount,category,dueDate,frequency,account) VALUES(?,?,?,?,?,?)",arrayOf(b.title,b.amount,b.category,b.dueDate,b.frequency,b.account))else writableDatabase.execSQL("UPDATE bills SET title=?,amount=?,category=?,dueDate=?,frequency=?,account=? WHERE id=?",arrayOf(b.title,b.amount,b.category,b.dueDate,b.frequency,b.account,b.id))}
 fun deleteBill(id:Long){writableDatabase.delete("bills","id=?",arrayOf(id.toString()))}
 fun restoreTransactions(rows:List<Tx>){
  val db=writableDatabase
  db.beginTransaction()
  try{
   db.execSQL("INSERT OR IGNORE INTO accounts(name,opening) VALUES('Cash',0)")
   db.delete("tx",null,null)
   rows.forEach{t->
    db.execSQL("INSERT OR IGNORE INTO categories(name,kind) VALUES(?,?)",arrayOf(t.category,if(t.kind=="Income")"Income" else "Expense"))
    db.execSQL("INSERT OR IGNORE INTO accounts(name,opening) VALUES(?,0)",arrayOf(t.account))
    db.execSQL("INSERT INTO tx(title,amount,kind,category,date,account) VALUES(?,?,?,?,?,?)",arrayOf(t.title,t.amount,t.kind,t.category,t.date,t.account))
   }
   db.setTransactionSuccessful()
  }finally{db.endTransaction()}
 }
 fun createBackupJson():String {
  val root=JSONObject().put("app","LoganTech Expenses Tracker").put("formatVersion",1).put("createdAt",java.time.Instant.now().toString())
  root.put("transactions",JSONArray().apply{all().forEach{t->put(JSONObject().put("id",t.id).put("title",t.title).put("amount",t.amount).put("kind",t.kind).put("category",t.category).put("date",t.date).put("account",t.account))}})
  val categoryKinds=linkedMapOf<String,String>()
  readableDatabase.rawQuery("SELECT name,kind FROM categories ORDER BY name",null).use{while(it.moveToNext())categoryKinds[it.getString(0)]=it.getString(1)}
  all().forEach{categoryKinds.putIfAbsent(it.category,"Both")}
  bills().forEach{categoryKinds.putIfAbsent(it.category,"Both")}
  budgets().forEach{categoryKinds.putIfAbsent(it.category,"Both")}
  root.put("categories",JSONArray().apply{categoryKinds.forEach{(name,kind)->put(JSONObject().put("name",name).put("kind",kind).put("icon",CUSTOM_CATEGORY_ICONS[name]?: ""))}})
  root.put("accounts",JSONArray().apply{accounts().forEach{a->put(JSONObject().put("id",a.id).put("name",a.name).put("opening",a.opening))}})
  root.put("budgets",JSONArray().apply{budgets().forEach{b->put(JSONObject().put("id",b.id).put("category",b.category).put("amount",b.amount).put("period",b.period))}})
  root.put("bills",JSONArray().apply{bills().forEach{b->put(JSONObject().put("id",b.id).put("title",b.title).put("amount",b.amount).put("category",b.category).put("dueDate",b.dueDate).put("frequency",b.frequency).put("account",b.account))}})
  return root.toString(2)
 }
 fun restoreBackupJson(source:String) {
  val root=JSONObject(source)
  require(root.optString("app")=="LoganTech Expenses Tracker"){"This file is not a LoganTech Expenses Tracker backup."}
  require(root.optInt("formatVersion",0)==1){"Unsupported backup version."}
  val txs=root.optJSONArray("transactions")?:throw IllegalArgumentException("Backup is missing transactions.")
  val cats=root.optJSONArray("categories")?:throw IllegalArgumentException("Backup is missing categories.")
  val accs=root.optJSONArray("accounts")?:throw IllegalArgumentException("Backup is missing accounts.")
  val buds=root.optJSONArray("budgets")?:throw IllegalArgumentException("Backup is missing budgets.")
  val billRows=root.optJSONArray("bills")?:throw IllegalArgumentException("Backup is missing bills.")
  require(accs.length()>0){"Backup must contain at least one account."}
  val seenTx=HashSet<Long>();val seenAcc=HashSet<Long>();val seenBud=HashSet<Long>();val seenBills=HashSet<Long>()
  for(i in 0 until txs.length()){val o=txs.getJSONObject(i);val id=o.getLong("id");val amount=o.getDouble("amount");require(id>0&&seenTx.add(id)){"Invalid or duplicate transaction ID."};require(amount.isFinite()&&amount>0){"Invalid transaction amount."};require(o.getString("kind")=="Income"||o.getString("kind")=="Expense"){"Invalid transaction type."};LocalDate.parse(o.getString("date"));require(o.getString("title").isNotBlank()&&o.getString("category").isNotBlank()&&o.getString("account").isNotBlank()){"Incomplete transaction record."}}
  for(i in 0 until cats.length()){val o=cats.getJSONObject(i);require(o.getString("name").isNotBlank()){"Invalid category."}}
  for(i in 0 until accs.length()){val o=accs.getJSONObject(i);val id=o.getLong("id");require(id>0&&seenAcc.add(id)&&o.getString("name").isNotBlank()&&o.getDouble("opening").isFinite()){"Invalid account record."}}
  for(i in 0 until buds.length()){val o=buds.getJSONObject(i);val id=o.getLong("id");require(id>0&&seenBud.add(id)&&o.getString("category").isNotBlank()&&o.getDouble("amount").isFinite()&&o.getDouble("amount")>0){"Invalid budget record."};require(o.getString("period") in listOf("Week","Month","Year")){"Invalid budget period."}}
  val categoryNames=(0 until cats.length()).map{cats.getJSONObject(it).getString("name")}.toHashSet()
  val accountNames=(0 until accs.length()).map{accs.getJSONObject(it).getString("name")}.toHashSet()
  for(i in 0 until txs.length()){val o=txs.getJSONObject(i);require(o.getString("category") in categoryNames){"A transaction refers to a category missing from the backup."};require(o.getString("account") in accountNames){"A transaction refers to an account missing from the backup."}}
  for(i in 0 until buds.length()){val o=buds.getJSONObject(i);require(o.getString("category") in categoryNames){"A budget refers to a category missing from the backup."}}
  for(i in 0 until billRows.length()){val o=billRows.getJSONObject(i);val id=o.getLong("id");require(id>0&&seenBills.add(id)&&o.getString("title").isNotBlank()&&o.getDouble("amount").isFinite()&&o.getDouble("amount")>0){"Invalid bill record."};LocalDate.parse(o.getString("dueDate"));require(o.getString("frequency") in listOf("One-time","Weekly","Monthly","Yearly")){"Invalid bill frequency."};require(o.getString("category") in categoryNames&&o.getString("account") in accountNames){"A bill refers to a missing category or account."}}
  val db=writableDatabase
  db.beginTransaction()
  try {
   db.delete("tx",null,null);db.delete("categories",null,null);db.delete("accounts",null,null);db.delete("budgets",null,null);db.delete("bills",null,null)
   for(i in 0 until cats.length()){val o=cats.getJSONObject(i);db.execSQL("INSERT INTO categories(name,kind,icon) VALUES(?,?,?)",arrayOf(o.getString("name"),o.optString("kind","Both"),o.optString("icon","")))}
   for(i in 0 until accs.length()){val o=accs.getJSONObject(i);db.execSQL("INSERT INTO accounts(id,name,opening) VALUES(?,?,?)",arrayOf(o.getLong("id"),o.getString("name"),o.getDouble("opening")))}
   for(i in 0 until txs.length()){val o=txs.getJSONObject(i);db.execSQL("INSERT INTO tx(id,title,amount,kind,category,date,account) VALUES(?,?,?,?,?,?,?)",arrayOf(o.getLong("id"),o.getString("title"),o.getDouble("amount"),o.getString("kind"),o.getString("category"),o.getString("date"),o.getString("account")))}
   for(i in 0 until buds.length()){val o=buds.getJSONObject(i);db.execSQL("INSERT INTO budgets(id,category,amount,period) VALUES(?,?,?,?)",arrayOf(o.getLong("id"),o.getString("category"),o.getDouble("amount"),o.getString("period")))}
   for(i in 0 until billRows.length()){val o=billRows.getJSONObject(i);db.execSQL("INSERT INTO bills(id,title,amount,category,dueDate,frequency,account) VALUES(?,?,?,?,?,?,?)",arrayOf(o.getLong("id"),o.getString("title"),o.getDouble("amount"),o.getString("category"),o.getString("dueDate"),o.getString("frequency"),o.getString("account")))}
   db.setTransactionSuccessful()
  } finally {db.endTransaction()}
 }

}

class MainActivity:Activity(){
 private lateinit var store:Store
 private lateinit var root:LinearLayout
 private var tab=0
 private var period="Month"
 private var chosenCategory:String?=null
 private var exportFormat="pdf"
 private var themeChoice=0
 private fun applyTheme(){
  themeChoice=getSharedPreferences("logantech_ui",MODE_PRIVATE).getInt("theme_choice",0)
  val systemDark=(resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES
  val dark=if(themeChoice==0)systemDark else themeChoice!=1
  val themeAccents=intArrayOf(0xFF4D9FFF.toInt(),0xFF4D9FFF.toInt(),0xFF58A6FF.toInt(),0xFF38D9A9.toInt(),0xFFB69CFF.toInt(),0xFFFFC45E.toInt(),0xFFFF82A7.toInt(),0xFF43D6D0.toInt(),0xFFE85D75.toInt(),0xFF6574CD.toInt(),0xFF8BCB88.toInt(),0xFFFF8A5B.toInt(),0xFF9AA4B2.toInt(),0xFF4AB6D8.toInt(),0xFFC27B45.toInt())
  val accent=themeAccents[themeChoice.coerceIn(0,themeAccents.lastIndex)]
  BG=if(dark)0xFF080E1B.toInt() else 0xFFF2F6FC.toInt()
  PANEL=if(dark)0xFF111D31.toInt() else 0xFFFFFFFF.toInt()
  GOLD=accent
  MUTED=if(dark)0xFF9CB2CE.toInt() else 0xFF53647A.toInt()
  COLORS=intArrayOf(accent,0xFF37C7F4.toInt(),0xFF8B9BFF.toInt(),0xFF54D6B0.toInt(),0xFFFFC857.toInt(),0xFFB58BFF.toInt(),0xFFFF7AA8.toInt(),0xFF66D9E8.toInt(),0xFF9AD86B.toInt(),0xFFFF9A65.toInt())
  window.statusBarColor=BG;window.navigationBarColor=BG
  @Suppress("DEPRECATION")
  window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or if(dark)0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
 }
 private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
 private fun text(s:String,size:Float=15f,color:Int=if(BG==0xFFF2F6FC.toInt())0xFF172033.toInt() else Color.WHITE):TextView=TextView(this).apply{text=s;textSize=size;setTextColor(color);setPadding(dp(12),dp(9),dp(12),dp(9));typeface=if(size>=19f)Typeface.create("sans-serif-medium",Typeface.NORMAL) else Typeface.create("sans-serif",Typeface.NORMAL)}
 private fun rounded(color:Int,radius:Float=18f,stroke:Int=0):android.graphics.drawable.GradientDrawable=android.graphics.drawable.GradientDrawable().apply{setColor(color);cornerRadius=dp(radius.toInt()).toFloat();if(stroke!=0)setStroke(dp(1),stroke)}
 private fun animateExpressive(view:View,pressed:Boolean){
  val target=if(pressed)0.965f else 1f
  SpringAnimation(view,DynamicAnimation.SCALE_X,target).apply{spring=SpringForce(target).apply{dampingRatio=0.58f;stiffness=SpringForce.STIFFNESS_MEDIUM};start()}
  SpringAnimation(view,DynamicAnimation.SCALE_Y,target).apply{spring=SpringForce(target).apply{dampingRatio=0.58f;stiffness=SpringForce.STIFFNESS_MEDIUM};start()}
 }
 private fun expressiveTouch(view:View){
  view.isHapticFeedbackEnabled=true
  view.setOnTouchListener{v,event->
   when(event.actionMasked){
    MotionEvent.ACTION_DOWN->{animateExpressive(v,true);v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)}
    MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->animateExpressive(v,false)
   }
   false
  }
 }
 private fun button(s:String,action:()->Unit):Button=Button(this).apply{text=s;setTextColor(if(GOLD==0xFFFFC45E.toInt())0xFF172033.toInt() else Color.WHITE);textSize=14f;isAllCaps=false;typeface=Typeface.create("sans-serif-medium",Typeface.BOLD);background=rounded(GOLD,24f);elevation=dp(3).toFloat();setPadding(dp(12),dp(5),dp(12),dp(5));expressiveTouch(this);setOnClickListener{action()}}
 private fun panel():LinearLayout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(10),dp(12),dp(10));background=rounded(PANEL,26f,if(BG==0xFFF2F6FC.toInt())0xFFDCE5F2.toInt() else 0xFF263B59.toInt());elevation=dp(2).toFloat();expressiveTouch(this)}
 private fun money(v:Double)="₹"+String.format(Locale("en","IN"),"%.2f",v)
 private fun dateNow()=SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date())
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);applyTheme();store=Store(this);store.categories();if(intent.getBooleanExtra("open_about",false))tab=6;scheduleMonthlyBackup();scheduleBillReminders();scheduleVersionChecks();showUpdateNotificationIfAvailable();if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),711);window.statusBarColor=BG;window.navigationBarColor=BG;@Suppress("DEPRECATION") window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;show()}
 private fun scheduleVersionChecks(){
  val constraints=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
  val periodic=PeriodicWorkRequestBuilder<VersionCheckWorker>(7,TimeUnit.DAYS).setConstraints(constraints).build()
  WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork("logantech_version_check",ExistingPeriodicWorkPolicy.KEEP,periodic)
 }
 private fun showUpdateNotificationIfAvailable(){
  val prefs=getSharedPreferences("logantech_updates",MODE_PRIVATE)
  if(!prefs.getBoolean("update_available",false))return
  if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
  val version=prefs.getString("latest_version","New version")?:"New version"
  val channel="logantech_app_updates"
  val manager=getSystemService(NOTIFICATION_SERVICE) as NotificationManager
  if(Build.VERSION.SDK_INT>=26)manager.createNotificationChannel(NotificationChannel(channel,"App updates",NotificationManager.IMPORTANCE_LOW).apply{description="Notifications when a newer LoganTech app version is available"})
  val open=Intent(this,MainActivity::class.java).apply{flags=Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP;putExtra("open_about",true)}
  val pending=PendingIntent.getActivity(this,731,open,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  val notification=Notification.Builder(this,channel).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("LoganTech update available").setContentText("Version $version is being downloaded to your Downloads folder").setContentIntent(pending).setAutoCancel(true).setTimeoutAfter(3000).build()
  manager.notify(731,notification)
 }
 private fun scheduleMonthlyBackup(){val request=PeriodicWorkRequestBuilder<MonthlyBackupWorker>(30,TimeUnit.DAYS).build();WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork("mymoney_monthly_backup",ExistingPeriodicWorkPolicy.KEEP,request)}
 private fun scheduleBillReminders(){val request=PeriodicWorkRequestBuilder<BillReminderWorker>(24,TimeUnit.HOURS).build();WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork("logantech_bill_reminders",ExistingPeriodicWorkPolicy.KEEP,request)}
 private fun show(){
  root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(BG);setPadding(dp(12),dp(10),dp(12),dp(10))}
  val head=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
  head.addView(text("Logantech expenses tracker",16f,GOLD).apply{isSingleLine=true;ellipsize=android.text.TextUtils.TruncateAt.END},LinearLayout.LayoutParams(0,-2,1f))
  head.addView(text(if(tab==6)"← BACK" else "ⓘ ABOUT",11f,GOLD).apply{setOnClickListener{tab=if(tab==6)0 else 6;show()}});root.addView(head)
  val body=ScrollView(this).apply{clipToPadding=false;isFillViewport=false}
  val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
  body.addView(content);root.addView(body,LinearLayout.LayoutParams(-1,0,1f))
  when(tab){0->records(content);1->analysis(content);2->budgetsPage(content);3->accountsPage(content);4->categoriesPage(content);5->billsPage(content);6->aboutPage(content);7->transferHistoryPage(content)}
  val nav=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=rounded(PANEL,20f);setPadding(dp(5),dp(5),dp(5),dp(5))}
  val labels=listOf("📒  Records","📊  Analysis","🎯  Budgets","🏦  Accounts","🎨  Categories","🧾  Bills")
  for(rowIndex in 0..1){
   val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
   for(column in 0..2){
    val index=rowIndex*3+column
    val item=TextView(this).apply{
     text=labels[index];textSize=11f;gravity=Gravity.CENTER;isSingleLine=true
     setTextColor(if(tab==index)GOLD else MUTED)
     setTypeface(null,if(tab==index)Typeface.BOLD else Typeface.NORMAL)
     setPadding(dp(3),dp(5),dp(3),dp(5));minHeight=dp(44)
     background=if(tab==index)rounded(0xFF34445B.toInt(),12f) else null
     setOnClickListener{tab=index;chosenCategory=null;show()}
    }
    row.addView(item,LinearLayout.LayoutParams(0,dp(46),1f).apply{setMargins(dp(2),dp(1),dp(2),dp(1))})
   }
   nav.addView(row,LinearLayout.LayoutParams(-1,dp(46)))
  }
  root.addView(nav,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
  setContentView(root)
  root.alpha=0f;root.translationY=dp(14).toFloat()
  root.animate().alpha(1f).translationY(0f).setDuration(360).setInterpolator(android.view.animation.OvershootInterpolator(0.72f)).start()
  root.setOnApplyWindowInsetsListener{view,insets->
   val left=insets.systemWindowInsetLeft;val top=insets.systemWindowInsetTop
   val right=insets.systemWindowInsetRight;val bottom=insets.systemWindowInsetBottom
   view.setPadding(dp(12)+left,dp(10)+top,dp(12)+right,dp(10)+bottom)
   insets.consumeSystemWindowInsets()
  }
  root.requestApplyInsets()
 }
 private fun aboutPage(c:LinearLayout){
  c.addView(text("ABOUT THIS APP",12f,MUTED))
  val hero=panel().apply{gravity=Gravity.CENTER;setPadding(dp(20),dp(22),dp(20),dp(22));background=rounded(PANEL,30f,0xFF2C75C7.toInt())}
  hero.addView(text("LOGANTECH",25f,if(BG==0xFFF2F6FC.toInt())0xFF172033.toInt() else Color.WHITE).apply{gravity=Gravity.CENTER;typeface=Typeface.create("sans-serif-black",Typeface.BOLD)})
  hero.addView(text("EXPENSES TRACKER",14f,GOLD).apply{gravity=Gravity.CENTER})
  hero.addView(text("VIBE CODED BY POWER WIZARD LABZ",15f,0xFF72D6A8.toInt()).apply{gravity=Gravity.CENTER;setPadding(dp(4),dp(18),dp(4),dp(8));typeface=Typeface.create("sans-serif-medium",Typeface.BOLD)})
  c.addView(hero)
  val details=panel()
  details.addView(text("Your money. Your overview.",18f,GOLD))
  details.addView(text("Track income, expenses, budgets, accounts and recurring bills in one place. Your financial data is stored locally on this device.",13f,MUTED))
  val version=try{packageManager.getPackageInfo(packageName,0).versionName?: "Unknown"}catch(_:Exception){"Unknown"}
  details.addView(text("Version  $version",12f,MUTED))
  c.addView(details)
  c.addView(button("❔  How to use"){showHowToDialog()})
  c.addView(text("APP THEME",12f,MUTED))
  val themePanel=panel()
  themePanel.addView(text("Choose your look",17f,GOLD))
  val names=listOf("System default","Light","Ocean blue","Emerald","Violet","Amber","Rose","Teal","Crimson","Indigo","Mint","Sunset","Graphite","Arctic","Copper")
  val chips=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
  var rowStart=0
  while(rowStart<names.size){
   val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
   for(i in rowStart until minOf(rowStart+3,names.size)){
    val selected=themeChoice==i
    val chip=Button(this).apply{text=names[i];textSize=10f;isAllCaps=false;setTextColor(if(selected&&GOLD!=0xFFFFC45E.toInt())Color.WHITE else if(selected)0xFF172033.toInt() else MUTED);background=rounded(if(selected)GOLD else BG,22f);setPadding(dp(2),dp(2),dp(2),dp(2));minHeight=dp(46);expressiveTouch(this);setOnClickListener{getSharedPreferences("logantech_ui",MODE_PRIVATE).edit().putInt("theme_choice",i).apply();applyTheme();show()}}
    row.addView(chip,LinearLayout.LayoutParams(0,dp(48),1f).apply{setMargins(dp(2),dp(3),dp(2),dp(3))})
   }
   if(names.size-rowStart<3)row.addView(View(this),LinearLayout.LayoutParams(0,dp(48),(3-(names.size-rowStart)).toFloat()))
   chips.addView(row)
   rowStart+=3
  }
  themePanel.addView(chips)
  themePanel.addView(text("System default follows your phone's light or dark appearance. The other themes use a dark surface with their own accent colour.",11f,MUTED))
  c.addView(themePanel)
  val updates=getSharedPreferences("logantech_updates",MODE_PRIVATE)
  val updatePanel=panel()
  updatePanel.addView(text("APP UPDATES",11f,MUTED))
  val available=updates.getBoolean("update_available",false)
  val latest=updates.getString("latest_version","")?:""
  val checked=updates.getLong("last_check_time",0L)
  val status=updates.getString("download_status","")?:""
  updatePanel.addView(text(if(available)"⬇  Update found • v$latest" else "✓  No newer version detected",16f,if(available)GOLD else 0xFF72D6A8.toInt()))
  if(status.isNotBlank())updatePanel.addView(text(status,12f,MUTED))
  updatePanel.addView(text(if(checked>0)"Last checked • "+SimpleDateFormat("dd MMM yyyy, hh:mm a",Locale.getDefault()).format(Date(checked)) else "Automatic update checks run once a week when internet is available.",11f,MUTED))
  c.addView(updatePanel)
  c.addView(button("↻  Check update"){
   val constraints=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
   val request=OneTimeWorkRequestBuilder<VersionCheckWorker>().setConstraints(constraints).build()
   WorkManager.getInstance(applicationContext).enqueueUniqueWork("logantech_manual_version_check",ExistingWorkPolicy.REPLACE,request)
   Toast.makeText(this,"Checking for updates…",Toast.LENGTH_SHORT).show()
   android.os.Handler(mainLooper).postDelayed({show()},18000)
  })
  c.addView(View(this),LinearLayout.LayoutParams(1,dp(14)))
  c.addView(button("←  Back to Records"){tab=0;show()})
 }
 private fun showHowToDialog(){
  val scroll=ScrollView(this)
  val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(8),dp(18),dp(8))}
  fun section(title:String,body:String){
   val card=panel()
   card.addView(text(title,17f,GOLD))
   card.addView(text(body,13f,MUTED))
   content.addView(card,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(10)})
  }
  section("1. Add income or an expense","Open Records and tap + Add transaction. Enter a description, amount, choose Expense or Income, select a category, account and date, then save. The transaction type controls the calculation; the category is its label. You can tap a record to edit it or long-press to delete it.")
  section("2. What each category means","Food — groceries, meals, snacks and restaurants. Transport — fuel, public transport, taxi and travel. Shopping — clothes, electronics and personal purchases. Bills — utilities, rent and household payments. Health — medicines, appointments and medical costs. Entertainment — movies, games and leisure. Salary — salary or other income; make sure the transaction type is set to Income. Other — anything that does not fit elsewhere. Categories you create are custom labels; use them consistently. The app does not force a category to be income or expense: the Expense/Income selector decides that.")
  val custom=store.categories().filter{it !in listOf("Food","Transport","Shopping","Bills","Health","Entertainment","Salary","Other")}
  if(custom.isNotEmpty())section("Your custom categories",custom.joinToString("\n"){"• "+categoryIcon(it)+"  "+it+" — use this for the purpose you defined when creating it."})
  section("3. How totals are calculated","Total income = the sum of all saved transactions marked Income. Total expenses = the sum of all saved transactions marked Expense. Current total balance = the opening balances of all accounts + all income − all expenses. These headline totals cover all dates, not just the current month. A transaction is counted once, using its saved amount and type.")
  section("4. How records are arranged and counted","Records are ordered newest date first. If two transactions share a date, the most recently created one appears first. The transaction count is the number of saved transaction entries, including both income and expenses. Editing changes the existing entry; deleting removes it from totals and counts.")
  section("5. Analysis and pie chart","Choose Day, Week, Month or Year to filter the analysis period. Only Expense transactions inside that period are included. Spending is grouped by category, each category amount is the sum of its expense entries, and its percentage is category spending ÷ total spending × 100. Categories are arranged from highest spending to lowest. Tap a chart segment to view its transactions.")
  section("6. Budgets and accounts","A category budget compares that category's Expense transactions within the selected week, month or year against your budget amount. Account balance = that account's opening balance + its Income transactions − its Expense transactions. Moving or editing a transaction can therefore change the relevant account balance.")
  section("7. Bills and backups","Bills are reminders until you record a payment. Choosing Mark as paid creates an Expense transaction in the selected account and category; recurring bills then move to their next due date. Use Export & backup to save a PDF summary, CSV transaction list or full JSON backup. A full JSON backup includes transactions, categories, accounts, budgets and bills.")
  scroll.addView(content)
  AlertDialog.Builder(this).setTitle("How to use LoganTech").setView(scroll).setPositiveButton("Got it",null).show()
 }
 private fun totals(list:List<Tx>,kind:String)=list.filter{it.kind==kind}.sumOf{it.amount}
 private fun records(c:LinearLayout){
  val data=store.all()
  val opening=store.accounts().sumOf{it.opening}
  val balance=opening+totals(data,"Income")-totals(data,"Expense")
  val now=Calendar.getInstance()
  val monthData=data.filter{t->try{val f=SimpleDateFormat("yyyy-MM-dd",Locale.US);val d=Calendar.getInstance().apply{time=f.parse(t.date)!!};d.get(Calendar.YEAR)==now.get(Calendar.YEAR)&&d.get(Calendar.MONTH)==now.get(Calendar.MONTH)}catch(_:Exception){false}}
  val monthIncome=totals(monthData,"Income")
  val monthExpense=totals(monthData,"Expense")
  val savings=monthIncome-monthExpense
  val savingsRate=if(monthIncome>0)(savings/monthIncome*100).toInt() else 0
  val hero=panel()
  val greeting=if(now.get(Calendar.HOUR_OF_DAY)<12)"GOOD MORNING" else if(now.get(Calendar.HOUR_OF_DAY)<17)"GOOD AFTERNOON" else "GOOD EVENING"
  hero.addView(text(greeting+"  •  "+SimpleDateFormat("EEE, dd MMM",Locale.getDefault()).format(Date()),11f,MUTED))
  hero.addView(text("Your total balance",13f,MUTED))
  hero.addView(text(money(balance),34f,GOLD).apply{typeface=Typeface.create("sans-serif-black",Typeface.BOLD)})
  val totalsRow=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
  totalsRow.addView(text("↗  "+money(totals(data,"Income"))+" income",13f,0xFF72D6A8.toInt()),LinearLayout.LayoutParams(0,-2,1f))
  totalsRow.addView(text("↘  "+money(totals(data,"Expense"))+" expense",13f,0xFFE58B7D.toInt()))
  hero.addView(totalsRow)
  c.addView(hero)
  val month=panel()
  month.addView(text("THIS MONTH",11f,MUTED))
  val monthRow=LinearLayout(this)
  monthRow.addView(LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;addView(text("Income",12f,MUTED));addView(text(money(monthIncome),17f,0xFF72D6A8.toInt()))},LinearLayout.LayoutParams(0,-2,1f))
  monthRow.addView(LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;addView(text("Spent",12f,MUTED));addView(text(money(monthExpense),17f,0xFFE58B7D.toInt()))},LinearLayout.LayoutParams(0,-2,1f))
  monthRow.addView(LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;addView(text("Saved",12f,MUTED));addView(text(money(savings),17f,if(savings>=0)GOLD else 0xFFE58B7D.toInt()))},LinearLayout.LayoutParams(0,-2,1f))
  month.addView(monthRow)
  month.addView(text(if(monthIncome>0)"Savings rate  •  $savingsRate%" else "Add income to see your savings rate",11f,MUTED))
  c.addView(month)
  val budgets=store.budgets()
  val alerts=budgets.mapNotNull{b->val spent=data.filter{it.kind=="Expense"&&it.category==b.category&&inBudgetPeriod(it.date,b.period)}.sumOf{it.amount};val pct=if(b.amount>0)(spent/b.amount*100).toInt() else 0;if(pct>=80)"${b.category}: $pct% of budget used" else null}
  if(alerts.isNotEmpty()){
   val warning=panel()
   warning.addView(text("⚠  BUDGET WATCH",12f,0xFFFFB86B.toInt()))
   alerts.take(3).forEach{warning.addView(text(it,13f,0xFFFFC27A.toInt()))}
   c.addView(warning)
  }
  val actions=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(2),dp(4),dp(2),dp(4))}
  val entryRow=LinearLayout(this).apply{gravity=Gravity.CENTER}
  entryRow.addView(button("＋  Add transaction"){editTransaction(null,"Expense")},LinearLayout.LayoutParams(-1,dp(52)))
  actions.addView(entryRow)
  actions.addView(button("📤  Export & Backup"){exportDialog()},LinearLayout.LayoutParams(-1,dp(50)).apply{topMargin=dp(16)})
  c.addView(actions)
  val prefs=getSharedPreferences("mymoney_backup",MODE_PRIVATE)
  val last=prefs.getLong("last_backup_time",0L)
  val backupStatus=panel()
  backupStatus.addView(text("🔄 AUTOMATIC BACKUP",11f,MUTED))
  backupStatus.addView(text(if(last>0)"Last successful backup  •  "+SimpleDateFormat("dd MMM yyyy, hh:mm a",Locale.getDefault()).format(Date(last)) else "First automatic backup is scheduled; it runs about every 30 days.",13f,if(last>0)0xFF72D6A8.toInt() else MUTED))
  if(prefs.getString("backup_state","")=="retrying")backupStatus.addView(text("Backup will retry automatically. Your data remains on this device.",11f,0xFFFFB86B.toInt()))
  c.addView(backupStatus)
  c.addView(text("Recent transactions",19f,GOLD))
  val search=EditText(this).apply{hint="Search title, category, account or date";setSingleLine(true);textSize=14f;setTextColor(if(BG==0xFFF2F6FC.toInt())0xFF172033.toInt() else Color.WHITE);setHintTextColor(MUTED);setPadding(dp(14),dp(10),dp(14),dp(10));background=rounded(PANEL,14f)}
  c.addView(search)
  val listHost=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
  c.addView(listHost)
  renderTransactions(listHost,data,"")
  search.addTextChangedListener(object:android.text.TextWatcher{
   override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
   override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){renderTransactions(listHost,data,s?.toString().orEmpty())}
   override fun afterTextChanged(s:android.text.Editable?){}
  })
 }
 private fun renderTransactions(host:LinearLayout,data:List<Tx>,query:String){
  host.removeAllViews()
  val q=query.trim().lowercase(Locale.ROOT)
  val shown=if(q.isBlank())data else data.filter{t->listOf(t.title,t.category,t.account,t.date,t.kind,t.amount.toString()).any{v->v.lowercase(Locale.ROOT).contains(q)}}
  if(shown.isEmpty()){host.addView(text(if(q.isBlank())"No entries yet. Add your first income or expense." else "No transactions match “$query”.",13f,MUTED));return}
  shown.forEach{t->
   val p=panel()
   p.addView(text(categoryIcon(t.category)+"  "+t.title+"  •  "+t.category,15f,COLORS[(store.categories().indexOf(t.category).coerceAtLeast(0))%COLORS.size]))
   p.addView(text(t.date+"  •  "+t.account+"     "+(if(t.kind=="Income")"+" else "−")+money(t.amount),13f,if(t.kind=="Income")0xFF72D6A8.toInt() else 0xFFE58B7D.toInt()))
   p.setOnClickListener{editTransaction(t)}
   p.setOnLongClickListener{AlertDialog.Builder(this).setMessage("Delete this transaction?").setPositiveButton("Delete"){_,_->store.remove(t.id);show()}.setNegativeButton("Cancel",null).show();true}
   host.addView(p)
  }
 }
 private fun editTransaction(old:Tx?,defaultKind:String="Expense"){val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),0)}
  val title=EditText(this).apply{hint="Description";setText(old?.title?:"")}
  val amount=EditText(this).apply{hint="Amount";inputType=8194;setText(if(old==null)"" else old.amount.toString())}
  val kind=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("Expense","Income"));setSelection(if((old?.kind?:defaultKind)=="Income")1 else 0)}
  val cats=store.categories().ifEmpty{listOf("Other")}
  val cat=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,cats);setSelection(cats.indexOf(old?.category).coerceAtLeast(0))}
  val accs=store.accounts().map{it.name}.ifEmpty{listOf("Cash")}
  val account=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,accs);setSelection(accs.indexOf(old?.account).coerceAtLeast(0))}
  val date=EditText(this).apply{hint="Date (YYYY-MM-DD)";setText(old?.date?:dateNow())}
  box.addView(title);box.addView(amount);box.addView(kind);box.addView(cat);box.addView(account);box.addView(date)
  AlertDialog.Builder(this).setTitle(if(old==null)"New transaction" else "Edit transaction").setView(box).setPositiveButton("Save"){_,_->val a=amount.text.toString().toDoubleOrNull();val d=date.text.toString();if(a!=null&&a>0&&validDate(d)){store.save(Tx(old?.id?:0L,title.text.toString().ifBlank{cat.selectedItem.toString()},a,kind.selectedItem.toString(),cat.selectedItem.toString(),d,account.selectedItem.toString()));show()}else Toast.makeText(this,"Enter a valid amount and date (YYYY-MM-DD)",Toast.LENGTH_LONG).show()}.setNegativeButton("Cancel",null).show()
 }
 private fun validDate(s:String):Boolean=try{val f=SimpleDateFormat("yyyy-MM-dd",Locale.US);f.isLenient=false;f.parse(s)!=null}catch(_:Exception){false}
 private fun matchesPeriod(date:String,selected:String,now:LocalDate=LocalDate.now()):Boolean=try{val d=LocalDate.parse(date);when(selected){"Day"->d==now;"Week"->d.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))==now.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));"Year"->d.year==now.year;else->d.year==now.year&&d.month==now.month}}catch(_:Exception){false}
 private fun filtered():List<Tx> = store.all().filter{matchesPeriod(it.date,period)}

 private fun billsPage(c:LinearLayout){
  val bills=store.bills()
  val today=LocalDate.now()
  val dated=bills.mapNotNull{b->try{b to ChronoUnit.DAYS.between(today,LocalDate.parse(b.dueDate))}catch(_:Exception){null}}
  val overdue=dated.count{it.second<0}
  val dueSoon=dated.count{it.second in 0L..7L}
  c.addView(text("🧾  Recurring payments & bills",22f,GOLD))
  c.addView(text("Track rent, utilities, subscriptions, loans and regular payments.",12f,MUTED))
  val stats=panel()
  val row=LinearLayout(this)
  row.addView(LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;addView(text("OVERDUE",10f,MUTED));addView(text(overdue.toString(),23f,if(overdue>0)0xFFFF8585.toInt() else 0xFF72D6A8.toInt()))},LinearLayout.LayoutParams(0,-2,1f))
  row.addView(LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;addView(text("DUE IN 7 DAYS",10f,MUTED));addView(text(dueSoon.toString(),23f,if(dueSoon>0)0xFFFFC27A.toInt() else 0xFF72D6A8.toInt()))},LinearLayout.LayoutParams(0,-2,1f))
  row.addView(LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;addView(text("ACTIVE BILLS",10f,MUTED));addView(text(bills.size.toString(),23f,GOLD))},LinearLayout.LayoutParams(0,-2,1f))
  stats.addView(row);c.addView(stats)
  c.addView(button("＋  Add a bill or recurring payment"){billDialog(null)})
  if(bills.isEmpty()){val empty=panel();empty.addView(text("🌱  No bills added yet",17f,GOLD));empty.addView(text("Add your first bill to get due-date tracking and payment reminders.",13f,MUTED));c.addView(empty);return}
  dated.sortedBy{it.second}.forEach{pair->
   val b=pair.first;val days=pair.second
   val status=when{days<0->"OVERDUE by "+(-days)+" day(s)";days==0L->"DUE TODAY";days<=3L->"DUE IN "+days+" day(s)";else->"Due in "+days+" days"}
   val color=if(days<0)0xFFFF8585.toInt() else if(days<=3L)0xFFFFC27A.toInt() else 0xFF72D6A8.toInt()
   val p=panel()
   p.addView(text(categoryIcon(b.category)+"  "+b.title,17f,COLORS[(store.categories().indexOf(b.category).coerceAtLeast(0))%COLORS.size]))
   p.addView(text(money(b.amount)+"   •   "+b.frequency+"   •   "+b.category,13f,MUTED))
   p.addView(text("Due "+b.dueDate+"  •  "+status,12f,color))
   p.addView(text("Account: "+b.account,11f,MUTED))
   val actions=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
   actions.addView(button("✓ Mark paid"){markBillPaid(b)},LinearLayout.LayoutParams(0,-2,1f).apply{setMargins(0,0,dp(4),0)})
   actions.addView(button("Edit"){billDialog(b)},LinearLayout.LayoutParams(0,-2,1f).apply{setMargins(dp(4),0,dp(4),0)})
   actions.addView(button("Delete"){AlertDialog.Builder(this@MainActivity).setMessage("Delete this bill reminder?").setPositiveButton("Delete"){_,_->store.deleteBill(b.id);show()}.setNegativeButton("Cancel",null).show()},LinearLayout.LayoutParams(0,-2,1f).apply{setMargins(dp(4),0,0,0)})
   p.addView(actions);c.addView(p)
  }
 }
 private fun billDialog(old:Bill?){
  val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),0)}
  val title=EditText(this).apply{hint="Bill name (e.g. Electricity)";setText(old?.title?:"")}
  val amount=EditText(this).apply{hint="Amount (₹)";inputType=8194;setText(old?.amount?.toString()?:"")}
  val cats=store.categories().filter{it!="Salary"}.ifEmpty{listOf("Bills")}
  val category=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,cats);setSelection(cats.indexOf(old?.category?:"Bills").coerceAtLeast(0))}
  val due=EditText(this).apply{hint="Next due date (YYYY-MM-DD)";setText(old?.dueDate?:dateNow())}
  val frequency=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("One-time","Weekly","Monthly","Yearly"));setSelection(listOf("One-time","Weekly","Monthly","Yearly").indexOf(old?.frequency?:"Monthly").coerceAtLeast(0))}
  val accounts=store.accounts().map{it.name}.ifEmpty{listOf("Cash")}
  val account=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,accounts);setSelection(accounts.indexOf(old?.account?:"Cash").coerceAtLeast(0))}
  box.addView(title);box.addView(amount);box.addView(text("Category",12f,MUTED));box.addView(category);box.addView(due);box.addView(text("Repeat",12f,MUTED));box.addView(frequency);box.addView(text("Pay from",12f,MUTED));box.addView(account)
  AlertDialog.Builder(this).setTitle(if(old==null)"Add bill / payment" else "Edit bill").setView(box).setPositiveButton("Save"){_,_->
   val value=amount.text.toString().toDoubleOrNull();val date=due.text.toString()
   if(title.text.isNotBlank()&&value!=null&&value>0&&validDate(date)){store.saveBill(Bill(old?.id?:0L,title.text.toString().trim(),value,category.selectedItem.toString(),date,frequency.selectedItem.toString(),account.selectedItem.toString()));show()}
   else Toast.makeText(this,"Enter a bill name, valid amount and date (YYYY-MM-DD)",Toast.LENGTH_LONG).show()
  }.setNegativeButton("Cancel",null).show()
 }
 private fun markBillPaid(b:Bill){
  try{
   store.save(Tx(0L,"Bill: "+b.title,b.amount,"Expense",b.category,dateNow(),b.account))
   if(b.frequency=="One-time")store.deleteBill(b.id) else{
    val today=LocalDate.now();var next=LocalDate.parse(b.dueDate)
    do{next=when(b.frequency){"Weekly"->next.plusWeeks(1);"Yearly"->next.plusYears(1);else->next.plusMonths(1)}}while(!next.isAfter(today))
    store.saveBill(b.copy(dueDate=next.toString()))
   }
   Toast.makeText(this,"Payment recorded as an expense",Toast.LENGTH_LONG).show();show()
  }catch(e:Exception){Toast.makeText(this,"Could not record payment: "+e.message,Toast.LENGTH_LONG).show()}
 }
 private fun analysis(c:LinearLayout){c.addView(text("📊  Spending insights",22f,GOLD));val r=LinearLayout(this);listOf("Day","Week","Month","Year").forEach{val b=button(it){period=it;show()};b.textSize=11f;r.addView(b,LinearLayout.LayoutParams(0,-2,1f).apply{setMargins(dp(2),0,dp(2),0)})};c.addView(r);val data=filtered().filter{it.kind=="Expense"};val total=data.sumOf{it.amount};val summary=panel();summary.addView(text("TOTAL SPENDING",11f,MUTED));summary.addView(text(money(total),29f,GOLD));summary.addView(text(period+" overview  •  "+data.size+" transactions",12f,MUTED));c.addView(summary);val grouped=data.groupBy{it.category}.mapValues{e->e.value.sumOf{it.amount}}.toList().sortedByDescending{it.second};if(grouped.isEmpty())c.addView(text("🌱  No expenses in this period yet. Add a transaction to see your chart."))else{val chart=PieView(this,grouped.toMap()){selected->val txs=data.filter{it.category==selected};AlertDialog.Builder(this).setTitle(categoryIcon(selected)+"  "+selected).setItems(txs.map{it.date+"  "+it.title+"  "+money(it.amount)}.toTypedArray(),null).setPositiveButton("Close",null).show()};c.addView(chart,LinearLayout.LayoutParams(-1,dp(260)));c.addView(text("CATEGORY BREAKDOWN",12f,MUTED));grouped.forEachIndexed{i,e->val p=panel();p.orientation=LinearLayout.HORIZONTAL;p.gravity=Gravity.CENTER_VERTICAL;p.addView(text(categoryIcon(e.first),23f));val details=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};details.addView(text(e.first,15f,COLORS[i%COLORS.size]));details.addView(text(((e.second/total)*100).toInt().toString()+"% of spending",11f,MUTED));p.addView(details,LinearLayout.LayoutParams(0,-2,1f));p.addView(text(money(e.second),14f,if(BG==0xFFF2F6FC.toInt())0xFF172033.toInt() else Color.WHITE));p.setOnClickListener{chart.performClick()};c.addView(p)}}}
 private fun budgetsPage(c:LinearLayout){c.addView(text("Budgets",22f,GOLD));c.addView(button("+ Set / update budget"){budgetDialog(null)});val list=store.all();store.budgets().forEach{b->val spent=list.filter{it.kind=="Expense"&&it.category==b.category&&inBudgetPeriod(it.date,b.period)}.sumOf{it.amount};val pct=if(b.amount>0)(spent/b.amount*100).toInt() else 0;val p=panel();p.addView(text(categoryIcon(b.category)+"  "+b.category+" • "+b.period,17f,COLORS[(store.categories().indexOf(b.category).coerceAtLeast(0))%COLORS.size]));p.addView(text(money(spent)+" spent of "+money(b.amount),14f));val bar=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{max=100;progress=pct.coerceIn(0,100);progressTintList=android.content.res.ColorStateList.valueOf(if(pct>=100)0xFFE58B7D.toInt() else if(pct>=80)0xFFFFB86B.toInt() else GOLD)};p.addView(bar,LinearLayout.LayoutParams(-1,dp(12)));p.addView(text(if(pct>=100)"⚠ Budget exceeded" else if(pct>=80)"⚠ $pct% used • getting close" else "$pct% used",12f,if(pct>=100)0xFFE58B7D.toInt() else MUTED));p.setOnClickListener{budgetDialog(b)};p.setOnLongClickListener{AlertDialog.Builder(this).setMessage("Delete this budget?").setPositiveButton("Delete"){_,_->store.deleteBudget(b.id);show()}.setNegativeButton("Cancel",null).show();true};c.addView(p)};if(store.budgets().isEmpty())c.addView(text("Create a category budget and track spending against it."))}
 private fun inBudgetPeriod(date:String,p:String):Boolean=matchesPeriod(date,p)
 private fun budgetDialog(old:Budget?){val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),0)};val cats=store.categories().filter{it!="Salary"}.ifEmpty{listOf("Other")};val cat=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,cats);setSelection(cats.indexOf(old?.category).coerceAtLeast(0))};val amount=EditText(this).apply{hint="Budget amount";inputType=8194;setText(old?.amount?.toString()?:"")};val period=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("Month","Week","Year"));setSelection(listOf("Month","Week","Year").indexOf(old?.period?: "Month"))};box.addView(cat);box.addView(amount);box.addView(period);AlertDialog.Builder(this).setTitle("Category budget").setView(box).setPositiveButton("Save"){_,_->val v=amount.text.toString().toDoubleOrNull();if(v!=null&&v>0){store.saveBudget(old?.id?:0L,cat.selectedItem.toString(),v,period.selectedItem.toString());show()}else Toast.makeText(this,"Enter a valid budget",Toast.LENGTH_SHORT).show()}.setNegativeButton("Cancel",null).show()}
 private fun accountsPage(c:LinearLayout){c.addView(text("Accounts",22f,GOLD));c.addView(button("+ Add account"){accountDialog(null)});store.accounts().forEach{a->val balance=a.opening+store.all().filter{it.account==a.name&&it.kind=="Income"}.sumOf{it.amount}-store.all().filter{it.account==a.name&&it.kind=="Expense"}.sumOf{it.amount};val p=panel();p.addView(text(categoryIcon(a.name)+"  "+a.name,17f,GOLD));p.addView(text("Current balance  "+money(balance),15f,GOLD));p.addView(text("Opening balance  "+money(a.opening),12f,MUTED));p.setOnClickListener{accountDialog(a)};p.setOnLongClickListener{if(a.name=="Cash"){Toast.makeText(this,"The default Cash account cannot be deleted",Toast.LENGTH_SHORT).show()}else AlertDialog.Builder(this).setMessage("Delete account? Existing transactions will remain but be assigned to Cash.").setPositiveButton("Delete"){_,_->store.all().filter{it.account==a.name}.forEach{store.save(it.copy(account="Cash"))};store.bills().filter{it.account==a.name}.forEach{store.saveBill(it.copy(account="Cash"))};store.deleteAccount(a.id);show()}.setNegativeButton("Cancel",null).show();true};c.addView(p)}}
 private fun accountDialog(old:Account?){val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),0)};val name=EditText(this).apply{hint="Account name";setText(old?.name?:"")};val opening=EditText(this).apply{hint="Opening balance";inputType=8194;setText(old?.opening?.toString()?:"0")};box.addView(name);box.addView(opening);AlertDialog.Builder(this).setTitle(if(old==null)"New account" else "Edit account").setView(box).setPositiveButton("Save"){_,_->val v=opening.text.toString().toDoubleOrNull();if(name.text.isNotBlank()&&v!=null){store.saveAccount(old?.id?:0L,name.text.toString().trim(),v);show()}else Toast.makeText(this,"Enter account name and valid balance",Toast.LENGTH_SHORT).show()}.setNegativeButton("Cancel",null).show()}
 private fun categoriesPage(c:LinearLayout){c.addView(text("Categories",22f,GOLD));c.addView(button("+ Add category"){val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),0)};val e=EditText(this).apply{hint="Category name"};val emoji=EditText(this).apply{hint="Emoji icon (e.g. 🧘 🍕 🐶)";setSingleLine(true);textSize=22f};box.addView(e);box.addView(emoji);AlertDialog.Builder(this).setTitle("New category").setMessage("Choose an emoji to use across transaction lists, category lists and charts.").setView(box).setPositiveButton("Add"){_,_->if(e.text.isNotBlank()){store.addCategory(e.text.toString().trim(),emoji.text.toString().trim());show()}}.setNegativeButton("Cancel",null).show()});store.categories().forEach{n->val p=panel();p.orientation=LinearLayout.HORIZONTAL;p.gravity=Gravity.CENTER_VERTICAL;p.addView(text(categoryIcon(n),24f));p.addView(text(n,16f,COLORS[(store.categories().indexOf(n).coerceAtLeast(0))%COLORS.size]),LinearLayout.LayoutParams(0,-2,1f));p.addView(text("⋮",20f,MUTED));p.setOnLongClickListener{AlertDialog.Builder(this).setMessage("Delete category $n? Existing transactions keep this category label.").setPositiveButton("Delete"){_,_->store.deleteCategory(n);show()}.setNegativeButton("Cancel",null).show();true};c.addView(p)}}

 private fun exportDialog(){
  val options=arrayOf("Export Excel-compatible spreadsheet (CSV)","Save PDF summary","Save CSV transaction report","Save FULL restore backup (JSON)","Import spreadsheet / backup","Import & export history","Share summary to Google Keep / Notes")
  AlertDialog.Builder(this).setTitle("Import & export").setItems(options){_,which->when(which){0->createExportFile("csv");1->createExportFile("pdf");2->createExportFile("csv");3->createExportFile("json");4->chooseBackupToRestore();5->{tab=7;show()};6->shareSummaryNote()}}.setNegativeButton("Cancel",null).show()
 }
 private fun historyDir():File=File(filesDir,"transfer-history").apply{if(!exists())mkdirs()}
 private fun saveTransferCopy(action:String,format:String,bytes:ByteArray){
  val stamp=SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(Date())
  val file=File(historyDir(),"$stamp-$action.$format");file.writeBytes(bytes)
  val prefs=getSharedPreferences("logantech_transfer_history",MODE_PRIVATE)
  val items=JSONArray(prefs.getString("items","[]")?:"[]")
  items.put(JSONObject().put("name",file.name).put("action",action).put("format",format).put("time",System.currentTimeMillis()).put("size",bytes.size))
  prefs.edit().putString("items",items.toString()).apply()
 }
 private fun transferHistoryPage(c:LinearLayout){
  c.addView(text("IMPORT & EXPORT HISTORY",12f,MUTED))
  c.addView(text("Each completed import or export keeps a private safety copy in this app's local storage. No broad storage permission is needed.",13f,MUTED))
  val items=try{JSONArray(getSharedPreferences("logantech_transfer_history",MODE_PRIVATE).getString("items","[]")?:"[]")}catch(_:Exception){JSONArray()}
  for(i in items.length()-1 downTo 0){
   val o=items.optJSONObject(i)?:continue;val card=panel()
   card.addView(text(o.optString("action").uppercase(Locale.ROOT)+" • "+o.optString("format").uppercase(Locale.ROOT),15f,GOLD))
   card.addView(text(SimpleDateFormat("dd MMM yyyy, hh:mm a",Locale.getDefault()).format(Date(o.optLong("time")))+" • "+o.optLong("size")+" bytes",11f,MUTED))
   val file=File(historyDir(),o.optString("name"))
   card.addView(button("Restore this safety copy"){
    if(!file.exists())Toast.makeText(this,"Safety copy not found",Toast.LENGTH_LONG).show()
    else try{val raw=file.readText(Charsets.UTF_8);if(raw.trimStart().startsWith("{"))confirmRestore(raw) else confirmLegacyCsvRestore(raw)}catch(e:Exception){Toast.makeText(this,"Could not restore: "+e.message,Toast.LENGTH_LONG).show()}
   })
   c.addView(card)
  }
  if(items.length()==0)c.addView(text("No transfers recorded yet."))
  c.addView(button("← Back to Records"){tab=0;show()})
 }
 private fun chooseBackupToRestore(){
  val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="*/*";addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)}
  try{startActivityForResult(intent,712)}catch(e:Exception){Toast.makeText(this,"Could not open file picker: "+e.message,Toast.LENGTH_LONG).show()}
 }
 private fun parseCsvRows(source:String):List<List<String>>{
  val rows=mutableListOf<List<String>>();var row=mutableListOf<String>();val field=StringBuilder();var quoted=false;var i=0
  while(i<source.length){val ch=source[i];when{
   quoted&&ch=='"'&&i+1<source.length&&source[i+1]=='"'->{field.append('"');i++}
   ch=='"'->{quoted=!quoted}
   ch==','&&!quoted->{row.add(field.toString());field.setLength(0)}
   (ch=='\n'||ch=='\r')&&!quoted->{row.add(field.toString());field.setLength(0);if(row.any{it.isNotBlank()})rows.add(row);row=mutableListOf();if(ch=='\r'&&i+1<source.length&&source[i+1]=='\n')i++}
   else->field.append(ch)
  };i++}
  if(field.isNotEmpty()||row.isNotEmpty()){row.add(field.toString());if(row.any{it.isNotBlank()})rows.add(row)}
  require(!quoted){"CSV backup contains an unfinished quoted field."}
  return rows
 }
 private fun confirmLegacyCsvRestore(csv:String){
  val rows=parseCsvRows(csv)
  require(rows.isNotEmpty()){"CSV backup is empty."}
  val headers=rows.first().map{it.trim().lowercase(Locale.ROOT)}
  fun col(vararg names:String):Int=names.firstNotNullOfOrNull{name->headers.indexOf(name).takeIf{it>=0}}?:-1
  val dateCol=col("date");val typeCol=col("type");val categoryCol=col("category");val titleCol=col("description","title");val amountCol=col("amount");val accountCol=col("account")
  require(listOf(dateCol,typeCol,categoryCol,titleCol,amountCol).all{it>=0}){"Unsupported CSV backup. Choose a LoganTech transaction CSV or a full JSON backup."}
  val restored=rows.drop(1).filter{it.any(String::isNotBlank)}.mapIndexed{index,r->
   fun value(i:Int)=r.getOrNull(i)?.trim().orEmpty()
   val date=value(dateCol);val kind=value(typeCol);val category=value(categoryCol);val title=value(titleCol);val amount=value(amountCol).replace(",","").toDoubleOrNull();val account=if(accountCol>=0)value(accountCol).ifBlank{"Cash"}else "Cash"
   require(kind=="Income"||kind=="Expense"){"Invalid type on CSV row "+(index+2)}
   require(amount!=null&&amount.isFinite()&&amount>0){"Invalid amount on CSV row "+(index+2)}
   LocalDate.parse(date)
   require(category.isNotBlank()&&title.isNotBlank()){"Missing details on CSV row "+(index+2)}
   Tx(0L,title,amount,kind,category,date,account)
  }
  AlertDialog.Builder(this).setTitle("Restore older CSV backup?")
   .setMessage("This CSV contains transactions only. It will replace the current transaction list and keep your existing accounts, budgets and bills. Missing accounts and categories will be added automatically. For a complete restore of all data, use a full JSON backup.")
   .setPositiveButton("Restore transactions"){_,_->
    try{store.restoreTransactions(restored);Toast.makeText(this,"Restored "+restored.size+" transactions from CSV.",Toast.LENGTH_LONG).show();show()}
    catch(e:Exception){Toast.makeText(this,"CSV restore failed: "+e.message,Toast.LENGTH_LONG).show()}
   }.setNegativeButton("Cancel",null).show()
 }
 private fun confirmRestore(json:String){
  AlertDialog.Builder(this).setTitle("Restore backup?")
   .setMessage("This replaces current transactions, categories, accounts, budgets and bills. A safety copy of your current data will be saved inside the app first.")
   .setPositiveButton("Restore data"){_,_->
    try{
     val safety=File(filesDir,"LoganTech-pre-restore-"+System.currentTimeMillis()+".json")
     safety.writeText(store.createBackupJson(),Charsets.UTF_8)
     store.restoreBackupJson(json)
     Toast.makeText(this,"Backup restored. All screens and calculations have been refreshed.",Toast.LENGTH_LONG).show()
     show()
    }catch(e:Exception){Toast.makeText(this,"Restore failed: "+(e.message?: "Invalid backup"),Toast.LENGTH_LONG).show()}
   }.setNegativeButton("Cancel",null).show()
 }
 private fun createExportFile(format:String){
  exportFormat=format
  val stamp=SimpleDateFormat("yyyyMMdd-HHmm",Locale.US).format(Date())
  val mime=when(format){"pdf"->"application/pdf";"json"->"application/json";else->"text/csv"}
  val intent=Intent(Intent.ACTION_CREATE_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type=mime;putExtra(Intent.EXTRA_TITLE,"LoganTechExpenses-$stamp.$format")}
  startActivityForResult(intent,710)
 }
 override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
  super.onActivityResult(requestCode,resultCode,data)
  if(requestCode==710&&resultCode==Activity.RESULT_OK){val uri=data?.data?:return
   try{val bytes=when(exportFormat){"pdf"->buildSummaryPdf();"json"->store.createBackupJson().toByteArray(Charsets.UTF_8);else->buildExcelCsv().toByteArray(Charsets.UTF_8)};contentResolver.openOutputStream(uri)?.use{it.write(bytes)}?:throw java.io.IOException("Could not write selected file");saveTransferCopy("export",exportFormat,bytes);Toast.makeText(this,"Export saved with a safety copy in History",Toast.LENGTH_LONG).show()}
   catch(e:Exception){Toast.makeText(this,"Export failed: "+e.message,Toast.LENGTH_LONG).show()}
  }
  if(requestCode==712&&resultCode==Activity.RESULT_OK){val uri=data?.data?:return
   try{val bytes=contentResolver.openInputStream(uri)?.use{it.readBytes()}?:throw java.io.IOException("Could not read backup");val content=bytes.toString(Charsets.UTF_8);saveTransferCopy("import-source","csv",bytes);if(content.trimStart().startsWith("{"))confirmRestore(content) else confirmLegacyCsvRestore(content)}
   catch(e:Exception){Toast.makeText(this,"Could not read backup: "+e.message,Toast.LENGTH_LONG).show()}
  }
 }
 private fun summaryText():String{
  val tx=store.all();val income=totals(tx,"Income");val expense=totals(tx,"Expense");val opening=store.accounts().sumOf{it.opening};val out=StringBuilder()
  out.append("LOGANTECH EXPENSES TRACKER FINANCIAL SUMMARY\n")
  out.append("Generated: ").append(SimpleDateFormat("dd MMM yyyy, HH:mm",Locale.getDefault()).format(Date())).append("\n\n")
  out.append("Total balance: ").append(money(opening+income-expense)).append("\n")
  out.append("Total income: ").append(money(income)).append("\n")
  out.append("Total expenses: ").append(money(expense)).append("\n")
  out.append("Transactions: ").append(tx.size).append("\n\nCATEGORY SPENDING\n")
  tx.filter{it.kind=="Expense"}.groupBy{it.category}.mapValues{e->e.value.sumOf{it.amount}}.toList().sortedByDescending{it.second}.forEach{out.append(it.first).append(": ").append(money(it.second)).append("\n")}
  out.append("\nACCOUNT BALANCES\n")
  store.accounts().forEach{a->val rows=tx.filter{it.account==a.name};out.append(a.name).append(": ").append(money(a.opening+totals(rows,"Income")-totals(rows,"Expense"))).append("\n")}
  out.append("\nUPCOMING BILLS\n")
  store.bills().forEach{b->out.append(b.title).append(" | ").append(money(b.amount)).append(" | ").append(b.dueDate).append(" | ").append(b.frequency).append(" | ").append(b.account).append("\n")}
  out.append("\nTRANSACTIONS\n")
  tx.forEach{t->out.append(t.date).append(" | ").append(t.kind).append(" | ").append(t.category).append(" | ").append(t.title).append(" | ").append(money(t.amount)).append(" | ").append(t.account).append("\n")}
  return out.toString()
 }
 private fun buildExcelCsv():String{
  val out=StringBuilder("\uFEFFDate,Type,Category,Description,Amount,Account\n")
  store.all().forEach{t->val fields=listOf(t.date,t.kind,t.category,t.title,String.format(Locale.US,"%.2f",t.amount),t.account);out.append(fields.joinToString(","){ "\"" + it.replace("\"","\"\"") + "\"" }).append("\n")}
  val grouped=store.all().filter{it.kind=="Expense"}.groupBy{it.category}.mapValues{it.value.sumOf{t->t.amount}}.toList().sortedByDescending{it.second}
  val total=grouped.sumOf{it.second}
  out.append("\nCATEGORY PIE CHART DATA\nCategory,Total Expense,Share Percent\n")
  grouped.forEach{(name,value)->out.append("\"").append(name.replace("\"","\"\"")).append("\",").append(String.format(Locale.US,"%.2f",value)).append(",").append(if(total>0)String.format(Locale.US,"%.2f",value/total*100)else "0").append("\n")}
  out.append("\nIn Excel: select the Category and Total Expense table, then Insert > Pie Chart. Recreate the chart after editing the data.")
  return out.toString()
 }
 private fun buildTransactionsCsv():String{
  val out=StringBuilder("Date,Type,Category,Description,Amount,Account\n")
  store.all().forEach{t->val fields=listOf(t.date,t.kind,t.category,t.title,t.amount.toString(),t.account);out.append(fields.joinToString(","){ "\""+it.replace("\"","\"\"")+"\"" }).append("\n")}
  return out.toString()
 }
 private fun buildSummaryPdf():ByteArray{
  val lines=summaryText().split("\n");val doc=PdfDocument();val pageHeight=maxOf(842,lines.size*19+90)
  val page=doc.startPage(PdfDocument.PageInfo.Builder(595,pageHeight,1).create());val canvas=page.canvas;val p=Paint(Paint.ANTI_ALIAS_FLAG);canvas.drawColor(Color.WHITE);var y=48f
  lines.forEachIndexed{index,line->p.color=if(index==0)0xFF172033.toInt() else if(line.endsWith("SPENDING")||line.endsWith("BALANCES")||line=="TRANSACTIONS")0xFF176B87.toInt() else 0xFF263238.toInt();p.textSize=if(index==0)19f else 10.5f;p.typeface=if(index==0||line.endsWith("SPENDING")||line.endsWith("BALANCES")||line=="TRANSACTIONS")Typeface.DEFAULT_BOLD else Typeface.DEFAULT;canvas.drawText(line.take(95),36f,y,p);y+=if(index==0)30f else 17f}
  doc.finishPage(page);val stream=ByteArrayOutputStream();doc.writeTo(stream);doc.close();return stream.toByteArray()
 }
 private fun shareSummaryNote(){
  val intent=Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_SUBJECT,"LoganTech Expenses Tracker financial summary");putExtra(Intent.EXTRA_TEXT,summaryText())}
  startActivity(Intent.createChooser(intent,"Save summary to Google Keep / Notes"))
 }

}
class PieView(ctx:android.content.Context,private val values:Map<String,Double>,private val onSelect:(String)->Unit):View(ctx){
 private val paint=Paint(3)
 private val label=Paint(3).apply{color=Color.WHITE;textAlign=Paint.Align.CENTER;typeface=Typeface.create("sans-serif-medium",Typeface.BOLD)}
 private var selected=-1
 private var chartRect=RectF()
 override fun onDraw(canvas:Canvas){super.onDraw(canvas);val total=values.values.sum();if(total<=0)return;val size=minOf(width,height).toFloat();val d=dp(18).toFloat();chartRect=RectF((width-size)/2+d,(height-size)/2+d,(width+size)/2-d,(height+size)/2-d);var start=-90f;val stroke=dp(30).toFloat();values.entries.forEachIndexed{i,e->paint.color=COLORS[i%COLORS.size];paint.style=Paint.Style.STROKE;paint.strokeWidth=stroke;paint.strokeCap=Paint.Cap.BUTT;val sweep=(e.value/total*360).toFloat();canvas.drawArc(chartRect,start+2f,(sweep-4f).coerceAtLeast(1f),false,paint);val mid=Math.toRadians((start+sweep/2).toDouble());val radius=chartRect.width()/2f;val mx=width/2f+kotlin.math.cos(mid).toFloat()*radius;val my=height/2f+kotlin.math.sin(mid).toFloat()*radius;paint.style=Paint.Style.FILL;paint.color=0xFF172235.toInt();canvas.drawCircle(mx,my,dp(12).toFloat(),paint);label.textSize=dp(13).toFloat();canvas.drawText(categoryIcon(e.key),mx,my+dp(5).toFloat(),label);start+=sweep};paint.style=Paint.Style.FILL;paint.color=PANEL;val cx=width/2f;val cy=height/2f;canvas.drawCircle(cx,cy,dp(49).toFloat(),paint);label.textSize=dp(11).toFloat();canvas.drawText("TOTAL SPENT",cx,cy-dp(5),label);label.textSize=dp(17).toFloat();canvas.drawText("₹"+String.format(Locale("en","IN"),"%.0f",total),cx,cy+dp(17),label)}
 override fun onTouchEvent(event:android.view.MotionEvent):Boolean{if(event.action==android.view.MotionEvent.ACTION_UP){val cx=width/2f;val cy=height/2f;var angle=Math.toDegrees(kotlin.math.atan2((event.y-cy).toDouble(),(event.x-cx).toDouble())).toFloat()+90f;if(angle<0)angle+=360f;val total=values.values.sum();var acc=0f;values.entries.forEach{val sweep=(it.value/total*360).toFloat();if(angle>=acc&&angle<acc+sweep){onSelect(it.key);performClick();return true};acc+=sweep}};return true}
 override fun performClick():Boolean{super.performClick();return true}
 private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
}



class VersionCheckWorker(context:android.content.Context,params:androidx.work.WorkerParameters):androidx.work.Worker(context,params){
 override fun doWork():androidx.work.ListenableWorker.Result{
  var connection:HttpURLConnection?=null
  return try{
   connection=(URL("https://api.github.com/repos/showlyshah/run-time-intelligence/releases/latest").openConnection() as HttpURLConnection).apply{
    requestMethod="GET";connectTimeout=15000;readTimeout=15000
    setRequestProperty("Accept","application/vnd.github+json")
    setRequestProperty("User-Agent","LoganTech-Expenses-Tracker-Android")
   }
   if(connection.responseCode!=200)return androidx.work.ListenableWorker.Result.retry()
   val release=JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use{it.readText()})
   val latest=release.optString("tag_name","").removePrefix("v").removePrefix("V").trim()
   if(latest.isBlank())return androidx.work.ListenableWorker.Result.retry()
   val assets=release.optJSONArray("assets")?:JSONArray()
   var apkUrl=""
   for(i in 0 until assets.length()){val asset=assets.optJSONObject(i)?:continue;val name=asset.optString("name","");if(name.endsWith(".apk",true)){apkUrl=asset.optString("browser_download_url","");if(apkUrl.isNotBlank())break}}
   val current=try{applicationContext.packageManager.getPackageInfo(applicationContext.packageName,0).versionName?:"0.0.0"}catch(_:Exception){"0.0.0"}
   val newer=compareVersions(latest,current)>0
   val prefs=applicationContext.getSharedPreferences("logantech_updates",0)
   val editor=prefs.edit().putBoolean("update_available",newer).putString("latest_version",latest).putString("release_notes",release.optString("body","").trim().take(450)).putLong("last_check_time",System.currentTimeMillis())
   if(newer&&apkUrl.isNotBlank()){
    val downloaded=prefs.getString("downloaded_version","")?:""
    if(downloaded!=latest){
     val request=DownloadManager.Request(android.net.Uri.parse(apkUrl))
      .setTitle("LoganTech Expenses Tracker v$latest")
      .setDescription("Downloading app update")
      .setMimeType("application/vnd.android.package-archive")
      .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
      .setAllowedOverMetered(true).setAllowedOverRoaming(false)
      .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,"LoganTechExpenses-v$latest.apk")
     val manager=applicationContext.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as DownloadManager
     val id=manager.enqueue(request)
     editor.putLong("download_id",id).putString("downloaded_version",latest).putString("download_status","Download started. Find the APK in Downloads when complete.")
    }else editor.putString("download_status","This update has already been downloaded.")
   }else if(newer)editor.putString("download_status","Update found, but this release has no APK file attached.")
   else editor.putString("download_status","You're using the latest available version.")
   editor.apply()
   androidx.work.ListenableWorker.Result.success()
  }catch(_:Exception){androidx.work.ListenableWorker.Result.retry()}
  finally{connection?.disconnect()}
 }
 private fun compareVersions(a:String,b:String):Int{
  val left=a.split(Regex("[^0-9]+")).filter{it.isNotBlank()}.map{it.toIntOrNull()?:0}
  val right=b.split(Regex("[^0-9]+")).filter{it.isNotBlank()}.map{it.toIntOrNull()?:0}
  for(i in 0 until maxOf(left.size,right.size)){val x=left.getOrElse(i){0};val y=right.getOrElse(i){0};if(x!=y)return x.compareTo(y)}
  return 0
 }
}

class MonthlyBackupWorker(context:android.content.Context,params:androidx.work.WorkerParameters):androidx.work.Worker(context,params){
 override fun doWork():androidx.work.ListenableWorker.Result{
  return try{
   val store=Store(applicationContext);val tx=store.all();val stamp=SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date())
   val csv=StringBuilder("Date,Type,Category,Description,Amount,Account\n")
   tx.forEach{t->val fields=listOf(t.date,t.kind,t.category,t.title,t.amount.toString(),t.account);csv.append(fields.joinToString(","){ "\""+it.replace("\"","\"\"")+"\"" }).append("\n")}
   val income=tx.filter{it.kind=="Income"}.sumOf{it.amount};val expense=tx.filter{it.kind=="Expense"}.sumOf{it.amount};val opening=store.accounts().sumOf{it.opening}
   val summary=buildString{
    append("LOGANTECH EXPENSES TRACKER MONTHLY BACKUP\nGenerated: ").append(stamp).append("\n")
    append("Total balance: ₹").append(String.format(Locale.US,"%.2f",opening+income-expense)).append("\n")
    append("Total income: ₹").append(String.format(Locale.US,"%.2f",income)).append("\n")
    append("Total expenses: ₹").append(String.format(Locale.US,"%.2f",expense)).append("\n")
    append("Transactions: ").append(tx.size).append("\n\nCATEGORY SPENDING\n")
    tx.filter{it.kind=="Expense"}.groupBy{it.category}.mapValues{e->e.value.sumOf{it.amount}}.toList().sortedByDescending{it.second}.forEach{append(it.first).append(": ₹").append(String.format(Locale.US,"%.2f",it.second)).append("\n")}
    append("\nTRANSACTIONS\n");tx.forEach{append(it.date).append(" | ").append(it.kind).append(" | ").append(it.category).append(" | ").append(it.title).append(" | ₹").append(it.amount).append(" | ").append(it.account).append("\n")}
   }
   saveBackup("LoganTechExpenses-$stamp.csv","text/csv",csv.toString().toByteArray(Charsets.UTF_8))
   saveBackup("LoganTechExpenses-$stamp-summary.txt","text/plain",summary.toByteArray(Charsets.UTF_8))
   saveBackup("LoganTechExpenses-$stamp-full-backup.json","application/json",store.createBackupJson().toByteArray(Charsets.UTF_8))
   val billCsv=StringBuilder("Bill,Amount,Category,Next Due Date,Frequency,Account\n")
   store.bills().forEach{b->billCsv.append(listOf(b.title,b.amount.toString(),b.category,b.dueDate,b.frequency,b.account).joinToString(","){ "\""+it.replace("\"","\"\"")+"\"" }).append("\n")}
   saveBackup("LoganTechExpenses-$stamp-bills.csv","text/csv",billCsv.toString().toByteArray(Charsets.UTF_8))
   applicationContext.getSharedPreferences("mymoney_backup",0).edit().putLong("last_backup_time",System.currentTimeMillis()).putString("backup_state","success").remove("backup_error").apply()
   androidx.work.ListenableWorker.Result.success()
  }catch(e:Exception){
   applicationContext.getSharedPreferences("mymoney_backup",0).edit().putString("backup_state","retrying").putString("backup_error",e.message?: "Backup failed").apply()
   androidx.work.ListenableWorker.Result.retry()
  }
 }
 private fun saveBackup(name:String,mime:String,bytes:ByteArray){
  if(android.os.Build.VERSION.SDK_INT>=29){
   val values=ContentValues().apply{put(MediaStore.MediaColumns.DISPLAY_NAME,name);put(MediaStore.MediaColumns.MIME_TYPE,mime);put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/LoganTechExpensesBackups")}
   val uri=applicationContext.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)?:throw java.io.IOException("Could not create backup file")
   applicationContext.contentResolver.openOutputStream(uri)?.use{it.write(bytes)}?:throw java.io.IOException("Could not write backup file")
  }else{
   val dir=java.io.File(applicationContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),"LoganTechExpensesBackups")
   if(!dir.exists()&&!dir.mkdirs())throw java.io.IOException("Could not create backup folder")
   java.io.File(dir,name).writeBytes(bytes)
  }
 }
}


class BillReminderWorker(context:android.content.Context,params:androidx.work.WorkerParameters):androidx.work.Worker(context,params){
 override fun doWork():androidx.work.ListenableWorker.Result{
  return try{
   val due=Store(applicationContext).bills().mapNotNull{b->try{b to ChronoUnit.DAYS.between(LocalDate.now(),LocalDate.parse(b.dueDate))}catch(_:Exception){null}}.filter{it.second<=3L}.sortedBy{it.second}
   if(due.isNotEmpty()){
    if(Build.VERSION.SDK_INT>=33&&applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return androidx.work.ListenableWorker.Result.success()
    val manager=applicationContext.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as NotificationManager
    val channel="logantech_bill_due"
    if(Build.VERSION.SDK_INT>=26)manager.createNotificationChannel(NotificationChannel(channel,"Bill reminders",NotificationManager.IMPORTANCE_DEFAULT).apply{description="Upcoming and overdue bill reminders"})
    val lines=due.take(5).joinToString("\n"){pair->val b=pair.first;val days=pair.second;when{days<0->"Overdue: "+b.title+" (₹"+b.amount+")";days==0L->"Due today: "+b.title+" (₹"+b.amount+")";else->"Due in "+days+" days: "+b.title+" (₹"+b.amount+")"}}
    val intent=Intent(applicationContext,MainActivity::class.java).apply{flags=Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP}
    val pending=PendingIntent.getActivity(applicationContext,712,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val notification=Notification.Builder(applicationContext,channel).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("LoganTech • Bill reminders").setContentText(due.size.toString()+" payment(s) need your attention").setStyle(Notification.BigTextStyle().bigText(lines)).setContentIntent(pending).setAutoCancel(true).build()
    manager.notify(712,notification)
   }
   androidx.work.ListenableWorker.Result.success()
  }catch(_:Exception){androidx.work.ListenableWorker.Result.retry()}
 }
}
