package com.sha.mymoney

import android.app.*
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

private const val BG = 0xFF101827.toInt()
private const val PANEL = 0xFF1D2A3D.toInt()
private const val GOLD = 0xFFFFC857.toInt()
private const val MUTED = 0xFFB7C5D8.toInt()
private val COLORS = intArrayOf(0xFFFF6B6B.toInt(),0xFF4ECDC4.toInt(),0xFF6C8CFF.toInt(),0xFFFFB84D.toInt(),0xFFC084FC.toInt(),0xFF7BD88F.toInt(),0xFFFF7AC6.toInt(),0xFF5CC8FF.toInt(),0xFFA3E635.toInt(),0xFFFF8A5B.toInt())
private fun categoryIcon(name:String):String=when(name.lowercase(Locale.ROOT)){"food","groceries","restaurant"->"🍔";"transport","travel","fuel"->"🚗";"shopping","clothes"->"🛍️";"bills","utilities","rent"->"🧾";"health","medical"->"💊";"entertainment","movies"->"🎬";"salary","income"->"💰";"education"->"📚";"home"->"🏠";"subscriptions"->"📱";"cash"->"💵";"bank"->"🏦";"savings"->"🐷";else->"✨"}

data class Tx(val id:Long,val title:String,val amount:Double,val kind:String,val category:String,val date:String,val account:String)
data class Budget(val id:Long,val category:String,val amount:Double,val period:String)
data class Account(val id:Long,val name:String,val opening:Double)

class Store(c:android.content.Context):SQLiteOpenHelper(c,"mymoney.db",null,3) {
 override fun onCreate(db:SQLiteDatabase) {
  db.execSQL("CREATE TABLE tx(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT,amount REAL,kind TEXT,category TEXT,date TEXT,account TEXT DEFAULT 'Cash')")
  db.execSQL("CREATE TABLE categories(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT UNIQUE,kind TEXT DEFAULT 'Both')")
  db.execSQL("CREATE TABLE accounts(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT UNIQUE,opening REAL DEFAULT 0)")
  db.execSQL("CREATE TABLE budgets(id INTEGER PRIMARY KEY AUTOINCREMENT,category TEXT UNIQUE,amount REAL,period TEXT DEFAULT 'Month')")
  seed(db)
 }
 private fun seed(db:SQLiteDatabase) {
  listOf("Food","Transport","Shopping","Bills","Health","Entertainment","Salary","Other").forEach{db.execSQL("INSERT OR IGNORE INTO categories(name,kind) VALUES(?,?)",arrayOf(it,if(it=="Salary")"Income" else "Expense"))}
  db.execSQL("INSERT OR IGNORE INTO accounts(name,opening) VALUES('Cash',0)")
 }
 override fun onUpgrade(db:SQLiteDatabase,old:Int,now:Int) {
  if(old<2){try{db.execSQL("ALTER TABLE tx ADD COLUMN account TEXT DEFAULT 'Cash'")}catch(_:Exception){}}
  if(old<3){db.execSQL("CREATE TABLE IF NOT EXISTS categories(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT UNIQUE,kind TEXT DEFAULT 'Both')");db.execSQL("CREATE TABLE IF NOT EXISTS accounts(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT UNIQUE,opening REAL DEFAULT 0)");db.execSQL("CREATE TABLE IF NOT EXISTS budgets(id INTEGER PRIMARY KEY AUTOINCREMENT,category TEXT UNIQUE,amount REAL,period TEXT DEFAULT 'Month')");seed(db)}
 }
 fun all():List<Tx>{val a=mutableListOf<Tx>();readableDatabase.rawQuery("SELECT id,title,amount,kind,category,date,COALESCE(account,'Cash') FROM tx ORDER BY date DESC,id DESC",null).use{while(it.moveToNext())a+=Tx(it.getLong(0),it.getString(1),it.getDouble(2),it.getString(3),it.getString(4),it.getString(5),it.getString(6))};return a}
 fun save(t:Tx){if(t.id==0L)writableDatabase.execSQL("INSERT INTO tx(title,amount,kind,category,date,account) VALUES(?,?,?,?,?,?)",arrayOf(t.title,t.amount,t.kind,t.category,t.date,t.account)) else writableDatabase.execSQL("UPDATE tx SET title=?,amount=?,kind=?,category=?,date=?,account=? WHERE id=?",arrayOf(t.title,t.amount,t.kind,t.category,t.date,t.account,t.id))}
 fun remove(id:Long){writableDatabase.delete("tx","id=?",arrayOf(id.toString()))}
 fun categories():List<String>{val a=mutableListOf<String>();readableDatabase.rawQuery("SELECT name FROM categories ORDER BY name",null).use{while(it.moveToNext())a+=it.getString(0)};return a}
 fun addCategory(n:String){writableDatabase.execSQL("INSERT OR IGNORE INTO categories(name,kind) VALUES(?,'Both')",arrayOf(n))}
 fun deleteCategory(n:String){writableDatabase.delete("categories","name=?",arrayOf(n))}
 fun accounts():List<Account>{val a=mutableListOf<Account>();readableDatabase.rawQuery("SELECT id,name,opening FROM accounts ORDER BY name",null).use{while(it.moveToNext())a+=Account(it.getLong(0),it.getString(1),it.getDouble(2))};return a}
 fun saveAccount(id:Long,n:String,v:Double){if(id==0L)writableDatabase.execSQL("INSERT OR IGNORE INTO accounts(name,opening) VALUES(?,?)",arrayOf(n,v)) else writableDatabase.execSQL("UPDATE accounts SET name=?,opening=? WHERE id=?",arrayOf(n,v,id))}
 fun deleteAccount(id:Long){writableDatabase.delete("accounts","id=?",arrayOf(id.toString()))}
 fun budgets():List<Budget>{val a=mutableListOf<Budget>();readableDatabase.rawQuery("SELECT id,category,amount,period FROM budgets ORDER BY category",null).use{while(it.moveToNext())a+=Budget(it.getLong(0),it.getString(1),it.getDouble(2),it.getString(3))};return a}
 fun saveBudget(id:Long,c:String,v:Double,p:String){writableDatabase.execSQL("INSERT OR REPLACE INTO budgets(id,category,amount,period) VALUES(COALESCE((SELECT id FROM budgets WHERE category=?),?),?,?,?)",arrayOf(c,if(id==0L)null else id,c,v,p))}
 fun deleteBudget(id:Long){writableDatabase.delete("budgets","id=?",arrayOf(id.toString()))}
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
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);store=Store(this);window.statusBarColor=BG;window.navigationBarColor=BG;show()}
 private fun show(){root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(BG);setPadding(dp(12),dp(10),dp(12),dp(4))}
  val head=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL};head.addView(text("💠 MyMoney",26f,GOLD),LinearLayout.LayoutParams(0,-2,1f));head.addView(text("SMART FINANCE",10f,MUTED));root.addView(head)
  val body=ScrollView(this);val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};body.addView(content);root.addView(body,LinearLayout.LayoutParams(-1,0,1f))
  when(tab){0->records(content);1->analysis(content);2->budgetsPage(content);3->accountsPage(content);4->categoriesPage(content)}
  val nav=LinearLayout(this).apply{background=rounded(PANEL,20f);gravity=Gravity.CENTER};listOf("📒\\nRecords","📊\\nAnalysis","🎯\\nBudgets","🏦\\nAccounts","🎨\\nCategories").forEachIndexed{i,n->val b=TextView(this).apply{text=n;textSize=10f;gravity=Gravity.CENTER;setTextColor(if(tab==i)GOLD else MUTED);setTypeface(null,if(tab==i)Typeface.BOLD else Typeface.NORMAL);setPadding(dp(2),dp(9),dp(2),dp(9));setOnClickListener{tab=i;chosenCategory=null;show()}};nav.addView(b,LinearLayout.LayoutParams(0,-2,1f))};root.addView(nav);setContentView(root)
 }
 private fun totals(list:List<Tx>,kind:String)=list.filter{it.kind==kind}.sumOf{it.amount}
 private fun records(c:LinearLayout){val data=store.all();val card=panel();card.addView(text("TOTAL BALANCE",12f,MUTED));val opening=store.accounts().sumOf{it.opening};card.addView(text(money(opening+totals(data,"Income")-totals(data,"Expense")),30f,GOLD));val row=LinearLayout(this);row.addView(text("Income  "+money(totals(data,"Income")),14f,0xFF72D6A8.toInt()),LinearLayout.LayoutParams(0,-2,1f));row.addView(text("Expense  "+money(totals(data,"Expense")),14f,0xFFE58B7D.toInt()));card.addView(row);c.addView(card);c.addView(button("+ Add transaction"){editTransaction(null)});c.addView(button("📤  Export & backup"){exportDialog()})
  c.addView(text("Recent transactions",19f,GOLD));if(data.isEmpty())c.addView(text("No entries yet. Add your first income or expense."));data.forEach{t->val p=panel();p.addView(text(categoryIcon(t.category)+"  "+t.title+"  •  "+t.category,16f,COLORS[(store.categories().indexOf(t.category).coerceAtLeast(0))%COLORS.size]));p.addView(text(t.date+"  •  "+t.account+"     "+(if(t.kind=="Income")"+" else "−")+money(t.amount),14f,if(t.kind=="Income")0xFF72D6A8.toInt() else 0xFFE58B7D.toInt()));p.setOnClickListener{editTransaction(t)};p.setOnLongClickListener{AlertDialog.Builder(this).setMessage("Delete this transaction?").setPositiveButton("Delete"){_,_->store.remove(t.id);show()}.setNegativeButton("Cancel",null).show();true};c.addView(p)}}
 private fun editTransaction(old:Tx?){val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),0)}
  val title=EditText(this).apply{hint="Description";setText(old?.title?:"")}
  val amount=EditText(this).apply{hint="Amount";inputType=8194;setText(if(old==null)"" else old.amount.toString())}
  val kind=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("Expense","Income"));setSelection(if(old?.kind=="Income")1 else 0)}
  val cats=store.categories().ifEmpty{listOf("Other")}
  val cat=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,cats);setSelection(cats.indexOf(old?.category).coerceAtLeast(0))}
  val accs=store.accounts().map{it.name}.ifEmpty{listOf("Cash")}
  val account=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,accs);setSelection(accs.indexOf(old?.account).coerceAtLeast(0))}
  val date=EditText(this).apply{hint="Date (YYYY-MM-DD)";setText(old?.date?:dateNow())}
  box.addView(title);box.addView(amount);box.addView(kind);box.addView(cat);box.addView(account);box.addView(date)
  AlertDialog.Builder(this).setTitle(if(old==null)"New transaction" else "Edit transaction").setView(box).setPositiveButton("Save"){_,_->val a=amount.text.toString().toDoubleOrNull();val d=date.text.toString();if(a!=null&&a>0&&validDate(d)){store.save(Tx(old?.id?:0L,title.text.toString().ifBlank{cat.selectedItem.toString()},a,kind.selectedItem.toString(),cat.selectedItem.toString(),d,account.selectedItem.toString()));show()}else Toast.makeText(this,"Enter a valid amount and date (YYYY-MM-DD)",Toast.LENGTH_LONG).show()}.setNegativeButton("Cancel",null).show()
 }
 private fun validDate(s:String):Boolean=try{val f=SimpleDateFormat("yyyy-MM-dd",Locale.US);f.isLenient=false;f.parse(s)!=null}catch(_:Exception){false}
 private fun filtered():List<Tx>{val now=Calendar.getInstance();return store.all().filter{t->try{val f=SimpleDateFormat("yyyy-MM-dd",Locale.US);f.isLenient=false;val x=Calendar.getInstance().apply{time=f.parse(t.date)!!};when(period){"Day"->now.get(Calendar.YEAR)==x.get(Calendar.YEAR)&&now.get(Calendar.DAY_OF_YEAR)==x.get(Calendar.DAY_OF_YEAR);"Week"->now.get(Calendar.YEAR)==x.get(Calendar.YEAR)&&now.get(Calendar.WEEK_OF_YEAR)==x.get(Calendar.WEEK_OF_YEAR);"Year"->now.get(Calendar.YEAR)==x.get(Calendar.YEAR);else->now.get(Calendar.YEAR)==x.get(Calendar.YEAR)&&now.get(Calendar.MONTH)==x.get(Calendar.MONTH)}}catch(_:Exception){false}}}
 private fun analysis(c:LinearLayout){c.addView(text("📊  Spending insights",22f,GOLD));val r=LinearLayout(this);listOf("Day","Week","Month","Year").forEach{val b=button(it){period=it;show()};b.textSize=11f;r.addView(b,LinearLayout.LayoutParams(0,-2,1f).apply{setMargins(dp(2),0,dp(2),0)})};c.addView(r);val data=filtered().filter{it.kind=="Expense"};val total=data.sumOf{it.amount};val summary=panel();summary.addView(text("TOTAL SPENDING",11f,MUTED));summary.addView(text(money(total),29f,GOLD));summary.addView(text(period+" overview  •  "+data.size+" transactions",12f,MUTED));c.addView(summary);val grouped=data.groupBy{it.category}.mapValues{e->e.value.sumOf{it.amount}}.toList().sortedByDescending{it.second};if(grouped.isEmpty())c.addView(text("🌱  No expenses in this period yet. Add a transaction to see your chart."))else{val chart=PieView(this,grouped.toMap()){selected->val txs=data.filter{it.category==selected};AlertDialog.Builder(this).setTitle(categoryIcon(selected)+"  "+selected).setItems(txs.map{it.date+"  "+it.title+"  "+money(it.amount)}.toTypedArray(),null).setPositiveButton("Close",null).show()};c.addView(chart,LinearLayout.LayoutParams(-1,dp(260)));c.addView(text("CATEGORY BREAKDOWN",12f,MUTED));grouped.forEachIndexed{i,e->val p=panel();p.orientation=LinearLayout.HORIZONTAL;p.gravity=Gravity.CENTER_VERTICAL;p.addView(text(categoryIcon(e.first),23f));val details=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};details.addView(text(e.first,15f,COLORS[i%COLORS.size]));details.addView(text(((e.second/total)*100).toInt().toString()+"% of spending",11f,MUTED));p.addView(details,LinearLayout.LayoutParams(0,-2,1f));p.addView(text(money(e.second),14f,Color.WHITE));p.setOnClickListener{chart.performClick()};c.addView(p)}}}
 private fun budgetsPage(c:LinearLayout){c.addView(text("Budgets",22f,GOLD));c.addView(button("+ Set / update budget"){budgetDialog(null)});val list=store.all();store.budgets().forEach{b->val spent=list.filter{it.kind=="Expense"&&it.category==b.category&&inBudgetPeriod(it.date,b.period)}.sumOf{it.amount};val pct=if(b.amount>0)(spent/b.amount*100).toInt() else 0;val p=panel();p.addView(text(categoryIcon(b.category)+"  "+b.category+" • "+b.period,17f,COLORS[(store.categories().indexOf(b.category).coerceAtLeast(0))%COLORS.size]));p.addView(text(money(spent)+" spent of "+money(b.amount),14f));val bar=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{max=100;progress=pct.coerceIn(0,100);progressTintList=android.content.res.ColorStateList.valueOf(if(pct>=100)0xFFE58B7D.toInt() else GOLD)};p.addView(bar,LinearLayout.LayoutParams(-1,dp(12)));p.addView(text(if(pct>=100)"Budget exceeded" else "$pct% used",12f,if(pct>=100)0xFFE58B7D.toInt() else MUTED));p.setOnClickListener{budgetDialog(b)};p.setOnLongClickListener{AlertDialog.Builder(this).setMessage("Delete this budget?").setPositiveButton("Delete"){_,_->store.deleteBudget(b.id);show()}.setNegativeButton("Cancel",null).show();true};c.addView(p)};if(store.budgets().isEmpty())c.addView(text("Create a category budget and track spending against it."))}
 private fun inBudgetPeriod(date:String,p:String):Boolean=try{val f=SimpleDateFormat("yyyy-MM-dd",Locale.US);val d=Calendar.getInstance().apply{time=f.parse(date)!!};val n=Calendar.getInstance();when(p){"Week"->d.get(Calendar.YEAR)==n.get(Calendar.YEAR)&&d.get(Calendar.WEEK_OF_YEAR)==n.get(Calendar.WEEK_OF_YEAR);"Year"->d.get(Calendar.YEAR)==n.get(Calendar.YEAR);else->d.get(Calendar.YEAR)==n.get(Calendar.YEAR)&&d.get(Calendar.MONTH)==n.get(Calendar.MONTH)}}catch(_:Exception){false}
 private fun budgetDialog(old:Budget?){val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),0)};val cats=store.categories().filter{it!="Salary"}.ifEmpty{listOf("Other")};val cat=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,cats);setSelection(cats.indexOf(old?.category).coerceAtLeast(0))};val amount=EditText(this).apply{hint="Budget amount";inputType=8194;setText(old?.amount?.toString()?:"")};val period=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("Month","Week","Year"));setSelection(listOf("Month","Week","Year").indexOf(old?.period?: "Month"))};box.addView(cat);box.addView(amount);box.addView(period);AlertDialog.Builder(this).setTitle("Category budget").setView(box).setPositiveButton("Save"){_,_->val v=amount.text.toString().toDoubleOrNull();if(v!=null&&v>0){store.saveBudget(old?.id?:0L,cat.selectedItem.toString(),v,period.selectedItem.toString());show()}else Toast.makeText(this,"Enter a valid budget",Toast.LENGTH_SHORT).show()}.setNegativeButton("Cancel",null).show()}
 private fun accountsPage(c:LinearLayout){c.addView(text("Accounts",22f,GOLD));c.addView(button("+ Add account"){accountDialog(null)});store.accounts().forEach{a->val balance=a.opening+store.all().filter{it.account==a.name&&it.kind=="Income"}.sumOf{it.amount}-store.all().filter{it.account==a.name&&it.kind=="Expense"}.sumOf{it.amount};val p=panel();p.addView(text(categoryIcon(a.name)+"  "+a.name,17f,GOLD));p.addView(text("Current balance  "+money(balance),15f,GOLD));p.addView(text("Opening balance  "+money(a.opening),12f,MUTED));p.setOnClickListener{accountDialog(a)};p.setOnLongClickListener{if(a.name=="Cash"){Toast.makeText(this,"The default Cash account cannot be deleted",Toast.LENGTH_SHORT).show()}else AlertDialog.Builder(this).setMessage("Delete account? Existing transactions will remain but be assigned to Cash.").setPositiveButton("Delete"){_,_->store.all().filter{it.account==a.name}.forEach{store.save(it.copy(account="Cash"))};store.deleteAccount(a.id);show()}.setNegativeButton("Cancel",null).show();true};c.addView(p)}}
 private fun accountDialog(old:Account?){val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),0)};val name=EditText(this).apply{hint="Account name";setText(old?.name?:"")};val opening=EditText(this).apply{hint="Opening balance";inputType=8194;setText(old?.opening?.toString()?:"0")};box.addView(name);box.addView(opening);AlertDialog.Builder(this).setTitle(if(old==null)"New account" else "Edit account").setView(box).setPositiveButton("Save"){_,_->val v=opening.text.toString().toDoubleOrNull();if(name.text.isNotBlank()&&v!=null){store.saveAccount(old?.id?:0L,name.text.toString().trim(),v);show()}else Toast.makeText(this,"Enter account name and valid balance",Toast.LENGTH_SHORT).show()}.setNegativeButton("Cancel",null).show()}
 private fun categoriesPage(c:LinearLayout){c.addView(text("Categories",22f,GOLD));c.addView(button("+ Add category"){val e=EditText(this).apply{hint="Category name"};AlertDialog.Builder(this).setTitle("New category").setView(e).setPositiveButton("Add"){_,_->if(e.text.isNotBlank()){store.addCategory(e.text.toString().trim());show()}}.setNegativeButton("Cancel",null).show()});store.categories().forEach{n->val p=panel();p.orientation=LinearLayout.HORIZONTAL;p.gravity=Gravity.CENTER_VERTICAL;p.addView(text(categoryIcon(n),24f));p.addView(text(n,16f,COLORS[(store.categories().indexOf(n).coerceAtLeast(0))%COLORS.size]),LinearLayout.LayoutParams(0,-2,1f));p.addView(text("⋮",20f,MUTED));p.setOnLongClickListener{AlertDialog.Builder(this).setMessage("Delete category $n? Existing transactions keep this category label.").setPositiveButton("Delete"){_,_->store.deleteCategory(n);show()}.setNegativeButton("Cancel",null).show();true};c.addView(p)}}

 private fun exportDialog(){
  val options=arrayOf("Save PDF summary","Save CSV transaction report","Share summary to Google Keep / Notes")
  AlertDialog.Builder(this).setTitle("Export & backup").setItems(options){_,which->when(which){0->createExportFile("pdf");1->createExportFile("csv");2->shareSummaryNote()}}.setNegativeButton("Cancel",null).show()
 }
 private fun createExportFile(format:String){
  exportFormat=format
  val stamp=SimpleDateFormat("yyyyMMdd-HHmm",Locale.US).format(Date())
  val intent=Intent(Intent.ACTION_CREATE_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type=if(format=="pdf")"application/pdf" else "text/csv";putExtra(Intent.EXTRA_TITLE,"MyMoney-$stamp.$format")}
  startActivityForResult(intent,710)
 }
 override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
  super.onActivityResult(requestCode,resultCode,data)
  if(requestCode==710&&resultCode==Activity.RESULT_OK){val uri=data?.data?:return
   try{val bytes=if(exportFormat=="pdf")buildSummaryPdf() else buildTransactionsCsv().toByteArray(Charsets.UTF_8);contentResolver.openOutputStream(uri)?.use{it.write(bytes)};Toast.makeText(this,"MyMoney export saved successfully",Toast.LENGTH_LONG).show()}
   catch(e:Exception){Toast.makeText(this,"Export failed: "+e.message,Toast.LENGTH_LONG).show()}
  }
 }
 private fun summaryText():String{
  val tx=store.all();val income=totals(tx,"Income");val expense=totals(tx,"Expense");val opening=store.accounts().sumOf{it.opening};val out=StringBuilder()
  out.append("MYMONEY FINANCIAL SUMMARY\n")
  out.append("Generated: ").append(SimpleDateFormat("dd MMM yyyy, HH:mm",Locale.getDefault()).format(Date())).append("\n\n")
  out.append("Total balance: ").append(money(opening+income-expense)).append("\n")
  out.append("Total income: ").append(money(income)).append("\n")
  out.append("Total expenses: ").append(money(expense)).append("\n")
  out.append("Transactions: ").append(tx.size).append("\n\nCATEGORY SPENDING\n")
  tx.filter{it.kind=="Expense"}.groupBy{it.category}.mapValues{e->e.value.sumOf{it.amount}}.toList().sortedByDescending{it.second}.forEach{out.append(it.first).append(": ").append(money(it.second)).append("\n")}
  out.append("\nACCOUNT BALANCES\n")
  store.accounts().forEach{a->val rows=tx.filter{it.account==a.name};out.append(a.name).append(": ").append(money(a.opening+totals(rows,"Income")-totals(rows,"Expense"))).append("\n")}
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
  val intent=Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_SUBJECT,"MyMoney financial summary");putExtra(Intent.EXTRA_TEXT,summaryText())}
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
