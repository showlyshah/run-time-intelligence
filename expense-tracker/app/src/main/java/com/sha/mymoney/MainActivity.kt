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
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val BG = 0xFF101827.toInt()
private const val PANEL = 0xFF1D2A3D.toInt()
private const val GOLD = 0xFFFFC857.toInt()
private const val MUTED = 0xFFB7C5D8.toInt()
private val COLORS = intArrayOf(0xFFFF6B6B.toInt(),0xFF4ECDC4.toInt(),0xFF6C8CFF.toInt(),0xFFFFB84D.toInt(),0xFFC084FC.toInt(),0xFF7BD88F.toInt(),0xFFFF7AC6.toInt(),0xFF5CC8FF.toInt(),0xFFA3E635.toInt(),0xFFFF8A5B.toInt())
private fun categoryIcon(name:String):String=when(name.lowercase(Locale.ROOT)){"food","groceries","restaurant"->"🍔";"transport","travel","fuel"->"🚗";"shopping","clothes"->"🛍️";"bills","utilities","rent"->"🧾";"health","medical"->"💊";"entertainment","movies"->"🎬";"salary","income"->"💰";"education"->"📚";"home"->"🏠";"subscriptions"->"📱";"cash"->"💵";"bank"->"🏦";"savings"->"🐷";else->"✨"}

data class Tx(val id:Long,val title:String,val amount:Double,val kind:String,val category:String,val date:String,val account:String)
data class Budget(val id:Long,val category:String,val amount:Double,val period:String)
data class Account(val id:Long,val name:String,val opening:Double)
data class Bill(val id:Long,val title:String,val amount:Double,val category:String,val dueDate:String,val frequency:String,val account:String)

class Store(c:android.content.Context):SQLiteOpenHelper(c,"mymoney.db",null,4) {
 override fun onCreate(db:SQLiteDatabase) {
  db.execSQL("CREATE TABLE tx(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT,amount REAL,kind TEXT,category TEXT,date TEXT,account TEXT DEFAULT 'Cash')")
  db.execSQL("CREATE TABLE categories(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT UNIQUE,kind TEXT DEFAULT 'Both')")
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
 }
 fun all():List<Tx>{val a=mutableListOf<Tx>();readableDatabase.rawQuery("SELECT id,title,amount,kind,category,date,COALESCE(account,'Cash') FROM tx ORDER BY date DESC,id DESC",null).use{while(it.moveToNext())a+=Tx(it.getLong(0),it.getString(1),it.getDouble(2),it.getString(3),it.getString(4),it.getString(5),it.getString(6))};return a}
 fun save(t:Tx){if(t.id==0L)writableDatabase.execSQL("INSERT INTO tx(title,amount,kind,category,date,account) VALUES(?,?,?,?,?,?)",arrayOf(t.title,t.amount,t.kind,t.category,t.date,t.account)) else writableDatabase.execSQL("UPDATE tx SET title=?,amount=?,kind=?,category=?,date=?,account=? WHERE id=?",arrayOf(t.title,t.amount,t.kind,t.category,t.date,t.account,t.id))}
 fun remove(id:Long){writableDatabase.delete("tx","id=?",arrayOf(id.toString()))}
 fun categories():List<String>{val a=mutableListOf<String>();readableDatabase.rawQuery("SELECT name FROM categories ORDER BY name",null).use{while(it.moveToNext())a+=it.getString(0)};return a}
 fun addCategory(n:String){writableDatabase.execSQL("INSERT OR IGNORE INTO categories(name,kind) VALUES(?,'Both')",arrayOf(n))}
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
 fun createBackupJson():String {
  val root=JSONObject().put("app","LoganTech Expenses Tracker").put("formatVersion",1).put("createdAt",java.time.Instant.now().toString())
  root.put("transactions",JSONArray().apply{all().forEach{t->put(JSONObject().put("id",t.id).put("title",t.title).put("amount",t.amount).put("kind",t.kind).put("category",t.category).put("date",t.date).put("account",t.account))}})
  root.put("categories",JSONArray().apply{readableDatabase.rawQuery("SELECT name,kind FROM categories ORDER BY name",null).use{while(it.moveToNext())put(JSONObject().put("name",it.getString(0)).put("kind",it.getString(1)))}})
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
  for(i in 0 until billRows.length()){val o=billRows.getJSONObject(i);val id=o.getLong("id");require(id>0&&seenBills.add(id)&&o.getString("title").isNotBlank()&&o.getDouble("amount").isFinite()&&o.getDouble("amount")>0){"Invalid bill record."};LocalDate.parse(o.getString("dueDate"));require(o.getString("frequency") in listOf("One-time","Weekly","Monthly","Yearly")){"Invalid bill frequency."}}
  val db=writableDatabase
  db.beginTransaction()
  try {
   db.delete("tx",null,null);db.delete("categories",null,null);db.delete("accounts",null,null);db.delete("budgets",null,null);db.delete("bills",null,null)
   for(i in 0 until cats.length()){val o=cats.getJSONObject(i);db.execSQL("INSERT INTO categories(name,kind) VALUES(?,?)",arrayOf(o.getString("name"),o.optString("kind","Both")))}
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
 private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
 private fun text(s:String,size:Float=15f,color:Int=Color.WHITE):TextView=TextView(this).apply{text=s;textSize=size;setTextColor(color);setPadding(dp(12),dp(9),dp(12),dp(9));typeface=if(size>=19f)Typeface.create("sans-serif-medium",Typeface.NORMAL) else Typeface.create("sans-serif",Typeface.NORMAL)}
 private fun rounded(color:Int,radius:Float=18f,stroke:Int=0):android.graphics.drawable.GradientDrawable=android.graphics.drawable.GradientDrawable().apply{setColor(color);cornerRadius=dp(radius.toInt()).toFloat();if(stroke!=0)setStroke(dp(1),stroke)}
 private fun button(s:String,action:()->Unit):Button=Button(this).apply{text=s;setTextColor(0xFF172033.toInt());textSize=13f;isAllCaps=false;typeface=Typeface.DEFAULT_BOLD;background=rounded(GOLD,16f);setPadding(dp(8),dp(3),dp(8),dp(3));setOnClickListener{action()}}
 private fun panel():LinearLayout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(8),dp(10),dp(8));background=rounded(PANEL,18f,0xFF34465F.toInt())}
 private fun money(v:Double)="₹"+String.format(Locale("en","IN"),"%.2f",v)
 private fun dateNow()=SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date())
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);store=Store(this);scheduleMonthlyBackup();scheduleBillReminders();if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),711);window.statusBarColor=BG;window.navigationBarColor=BG;@Suppress("DEPRECATION") window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;show()}
 private fun scheduleMonthlyBackup(){val request=PeriodicWorkRequestBuilder<MonthlyBackupWorker>(30,TimeUnit.DAYS).build();WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork("mymoney_monthly_backup",ExistingPeriodicWorkPolicy.KEEP,request)}
 private fun scheduleBillReminders(){val request=PeriodicWorkRequestBuilder<BillReminderWorker>(24,TimeUnit.HOURS).build();WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork("logantech_bill_reminders",ExistingPeriodicWorkPolicy.KEEP,request)}
 private fun show(){
  root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(BG);setPadding(dp(12),dp(10),dp(12),dp(10))}
  val head=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
  head.addView(text("💠 LoganTech Expenses Tracker",20f,GOLD),LinearLayout.LayoutParams(0,-2,1f))
  head.addView(text("SMART FINANCE",10f,MUTED));root.addView(head)
  val body=ScrollView(this).apply{clipToPadding=false;isFillViewport=false}
  val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
  body.addView(content);root.addView(body,LinearLayout.LayoutParams(-1,0,1f))
  when(tab){0->records(content);1->analysis(content);2->budgetsPage(content);3->accountsPage(content);4->categoriesPage(content);5->billsPage(content)}
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
  root.setOnApplyWindowInsetsListener{view,insets->
   val left=insets.systemWindowInsetLeft;val top=insets.systemWindowInsetTop
   val right=insets.systemWindowInsetRight;val bottom=insets.systemWindowInsetBottom
   view.setPadding(dp(12)+left,dp(10)+top,dp(12)+right,dp(10)+bottom)
   insets.consumeSystemWindowInsets()
  }
  root.requestApplyInsets()
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
  hero.addView(text(money(balance),31f,GOLD))
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
  entryRow.addView(button("＋ Expense"){editTransaction(null,"Expense")},LinearLayout.LayoutParams(0,dp(52),1f).apply{setMargins(0,0,dp(7),0)})
  entryRow.addView(button("＋ Income"){editTransaction(null,"Income")},LinearLayout.LayoutParams(0,dp(52),1f).apply{setMargins(dp(7),0,0,0)})
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
  val search=EditText(this).apply{hint="Search title, category, account or date";setSingleLine(true);textSize=14f;setTextColor(Color.WHITE);setHintTextColor(MUTED);setPadding(dp(14),dp(10),dp(14),dp(10));background=rounded(PANEL,14f)}
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
 private fun analysis(c:LinearLayout){c.addView(text("📊  Spending insights",22f,GOLD));val r=LinearLayout(this);listOf("Day","Week","Month","Year").forEach{val b=button(it){period=it;show()};b.textSize=11f;r.addView(b,LinearLayout.LayoutParams(0,-2,1f).apply{setMargins(dp(2),0,dp(2),0)})};c.addView(r);val data=filtered().filter{it.kind=="Expense"};val total=data.sumOf{it.amount};val summary=panel();summary.addView(text("TOTAL SPENDING",11f,MUTED));summary.addView(text(money(total),29f,GOLD));summary.addView(text(period+" overview  •  "+data.size+" transactions",12f,MUTED));c.addView(summary);val grouped=data.groupBy{it.category}.mapValues{e->e.value.sumOf{it.amount}}.toList().sortedByDescending{it.second};if(grouped.isEmpty())c.addView(text("🌱  No expenses in this period yet. Add a transaction to see your chart."))else{val chart=PieView(this,grouped.toMap()){selected->val txs=data.filter{it.category==selected};AlertDialog.Builder(this).setTitle(categoryIcon(selected)+"  "+selected).setItems(txs.map{it.date+"  "+it.title+"  "+money(it.amount)}.toTypedArray(),null).setPositiveButton("Close",null).show()};c.addView(chart,LinearLayout.LayoutParams(-1,dp(260)));c.addView(text("CATEGORY BREAKDOWN",12f,MUTED));grouped.forEachIndexed{i,e->val p=panel();p.orientation=LinearLayout.HORIZONTAL;p.gravity=Gravity.CENTER_VERTICAL;p.addView(text(categoryIcon(e.first),23f));val details=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};details.addView(text(e.first,15f,COLORS[i%COLORS.size]));details.addView(text(((e.second/total)*100).toInt().toString()+"% of spending",11f,MUTED));p.addView(details,LinearLayout.LayoutParams(0,-2,1f));p.addView(text(money(e.second),14f,Color.WHITE));p.setOnClickListener{chart.performClick()};c.addView(p)}}}
 private fun budgetsPage(c:LinearLayout){c.addView(text("Budgets",22f,GOLD));c.addView(button("+ Set / update budget"){budgetDialog(null)});val list=store.all();store.budgets().forEach{b->val spent=list.filter{it.kind=="Expense"&&it.category==b.category&&inBudgetPeriod(it.date,b.period)}.sumOf{it.amount};val pct=if(b.amount>0)(spent/b.amount*100).toInt() else 0;val p=panel();p.addView(text(categoryIcon(b.category)+"  "+b.category+" • "+b.period,17f,COLORS[(store.categories().indexOf(b.category).coerceAtLeast(0))%COLORS.size]));p.addView(text(money(spent)+" spent of "+money(b.amount),14f));val bar=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{max=100;progress=pct.coerceIn(0,100);progressTintList=android.content.res.ColorStateList.valueOf(if(pct>=100)0xFFE58B7D.toInt() else if(pct>=80)0xFFFFB86B.toInt() else GOLD)};p.addView(bar,LinearLayout.LayoutParams(-1,dp(12)));p.addView(text(if(pct>=100)"⚠ Budget exceeded" else if(pct>=80)"⚠ $pct% used • getting close" else "$pct% used",12f,if(pct>=100)0xFFE58B7D.toInt() else MUTED));p.setOnClickListener{budgetDialog(b)};p.setOnLongClickListener{AlertDialog.Builder(this).setMessage("Delete this budget?").setPositiveButton("Delete"){_,_->store.deleteBudget(b.id);show()}.setNegativeButton("Cancel",null).show();true};c.addView(p)};if(store.budgets().isEmpty())c.addView(text("Create a category budget and track spending against it."))}
 private fun inBudgetPeriod(date:String,p:String):Boolean=matchesPeriod(date,p)
 private fun budgetDialog(old:Budget?){val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),0)};val cats=store.categories().filter{it!="Salary"}.ifEmpty{listOf("Other")};val cat=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,cats);setSelection(cats.indexOf(old?.category).coerceAtLeast(0))};val amount=EditText(this).apply{hint="Budget amount";inputType=8194;setText(old?.amount?.toString()?:"")};val period=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("Month","Week","Year"));setSelection(listOf("Month","Week","Year").indexOf(old?.period?: "Month"))};box.addView(cat);box.addView(amount);box.addView(period);AlertDialog.Builder(this).setTitle("Category budget").setView(box).setPositiveButton("Save"){_,_->val v=amount.text.toString().toDoubleOrNull();if(v!=null&&v>0){store.saveBudget(old?.id?:0L,cat.selectedItem.toString(),v,period.selectedItem.toString());show()}else Toast.makeText(this,"Enter a valid budget",Toast.LENGTH_SHORT).show()}.setNegativeButton("Cancel",null).show()}
 private fun accountsPage(c:LinearLayout){c.addView(text("Accounts",22f,GOLD));c.addView(button("+ Add account"){accountDialog(null)});store.accounts().forEach{a->val balance=a.opening+store.all().filter{it.account==a.name&&it.kind=="Income"}.sumOf{it.amount}-store.all().filter{it.account==a.name&&it.kind=="Expense"}.sumOf{it.amount};val p=panel();p.addView(text(categoryIcon(a.name)+"  "+a.name,17f,GOLD));p.addView(text("Current balance  "+money(balance),15f,GOLD));p.addView(text("Opening balance  "+money(a.opening),12f,MUTED));p.setOnClickListener{accountDialog(a)};p.setOnLongClickListener{if(a.name=="Cash"){Toast.makeText(this,"The default Cash account cannot be deleted",Toast.LENGTH_SHORT).show()}else AlertDialog.Builder(this).setMessage("Delete account? Existing transactions will remain but be assigned to Cash.").setPositiveButton("Delete"){_,_->store.all().filter{it.account==a.name}.forEach{store.save(it.copy(account="Cash"))};store.deleteAccount(a.id);show()}.setNegativeButton("Cancel",null).show();true};c.addView(p)}}
 private fun accountDialog(old:Account?){val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),0)};val name=EditText(this).apply{hint="Account name";setText(old?.name?:"")};val opening=EditText(this).apply{hint="Opening balance";inputType=8194;setText(old?.opening?.toString()?:"0")};box.addView(name);box.addView(opening);AlertDialog.Builder(this).setTitle(if(old==null)"New account" else "Edit account").setView(box).setPositiveButton("Save"){_,_->val v=opening.text.toString().toDoubleOrNull();if(name.text.isNotBlank()&&v!=null){store.saveAccount(old?.id?:0L,name.text.toString().trim(),v);show()}else Toast.makeText(this,"Enter account name and valid balance",Toast.LENGTH_SHORT).show()}.setNegativeButton("Cancel",null).show()}
 private fun categoriesPage(c:LinearLayout){c.addView(text("Categories",22f,GOLD));c.addView(button("+ Add category"){val e=EditText(this).apply{hint="Category name"};AlertDialog.Builder(this).setTitle("New category").setView(e).setPositiveButton("Add"){_,_->if(e.text.isNotBlank()){store.addCategory(e.text.toString().trim());show()}}.setNegativeButton("Cancel",null).show()});store.categories().forEach{n->val p=panel();p.orientation=LinearLayout.HORIZONTAL;p.gravity=Gravity.CENTER_VERTICAL;p.addView(text(categoryIcon(n),24f));p.addView(text(n,16f,COLORS[(store.categories().indexOf(n).coerceAtLeast(0))%COLORS.size]),LinearLayout.LayoutParams(0,-2,1f));p.addView(text("⋮",20f,MUTED));p.setOnLongClickListener{AlertDialog.Builder(this).setMessage("Delete category $n? Existing transactions keep this category label.").setPositiveButton("Delete"){_,_->store.deleteCategory(n);show()}.setNegativeButton("Cancel",null).show();true};c.addView(p)}}

 private fun exportDialog(){
  val options=arrayOf("Save PDF summary","Save CSV transaction report","Save FULL restore backup (JSON)","Restore from backup (JSON)","Share summary to Google Keep / Notes")
  AlertDialog.Builder(this).setTitle("Export & backup").setItems(options){_,which->when(which){0->createExportFile("pdf");1->createExportFile("csv");2->createExportFile("json");3->chooseBackupToRestore();4->shareSummaryNote()}}.setNegativeButton("Cancel",null).show()
 }
 private fun chooseBackupToRestore(){
  val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="application/json";addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)}
  try{startActivityForResult(intent,712)}catch(e:Exception){Toast.makeText(this,"Could not open file picker: "+e.message,Toast.LENGTH_LONG).show()}
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
   try{val bytes=when(exportFormat){"pdf"->buildSummaryPdf();"json"->store.createBackupJson().toByteArray(Charsets.UTF_8);else->buildTransactionsCsv().toByteArray(Charsets.UTF_8)};contentResolver.openOutputStream(uri)?.use{it.write(bytes)}?:throw java.io.IOException("Could not write selected file");Toast.makeText(this,"LoganTech Expenses Tracker export saved successfully",Toast.LENGTH_LONG).show()}
   catch(e:Exception){Toast.makeText(this,"Export failed: "+e.message,Toast.LENGTH_LONG).show()}
  }
  if(requestCode==712&&resultCode==Activity.RESULT_OK){val uri=data?.data?:return
   try{val json=contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use{it.readText()}?:throw java.io.IOException("Could not read selected file");confirmRestore(json)}
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
 override fun onDraw(canvas:Canvas){super.onDraw(canvas);val total=values.values.sum();if(total<=0)return;val size=minOf(width,height).toFloat();val d=dp(18).toFloat();chartRect=RectF((width-size)/2+d,(height-size)/2+d,(width+size)/2-d,(height+size)/2-d);var start=-90f;val stroke=dp(30).toFloat();values.entries.forEachIndexed{i,e->paint.color=COLORS[i%COLORS.size];paint.style=Paint.Style.STROKE;paint.strokeWidth=stroke;paint.strokeCap=Paint.Cap.BUTT;val sweep=(e.value/total*360).toFloat();canvas.drawArc(chartRect,start+2f,(sweep-4f).coerceAtLeast(1f),false,paint);start+=sweep};paint.style=Paint.Style.FILL;paint.color=PANEL;val cx=width/2f;val cy=height/2f;canvas.drawCircle(cx,cy,dp(49).toFloat(),paint);label.textSize=dp(11).toFloat();canvas.drawText("TOTAL SPENT",cx,cy-dp(5),label);label.textSize=dp(17).toFloat();canvas.drawText("₹"+String.format(Locale("en","IN"),"%.0f",total),cx,cy+dp(17),label)}
 override fun onTouchEvent(event:android.view.MotionEvent):Boolean{if(event.action==android.view.MotionEvent.ACTION_UP){val cx=width/2f;val cy=height/2f;var angle=Math.toDegrees(kotlin.math.atan2((event.y-cy).toDouble(),(event.x-cx).toDouble())).toFloat()+90f;if(angle<0)angle+=360f;val total=values.values.sum();var acc=0f;values.entries.forEach{val sweep=(it.value/total*360).toFloat();if(angle>=acc&&angle<acc+sweep){onSelect(it.key);performClick();return true};acc+=sweep}};return true}
 override fun performClick():Boolean{super.performClick();return true}
 private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
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
