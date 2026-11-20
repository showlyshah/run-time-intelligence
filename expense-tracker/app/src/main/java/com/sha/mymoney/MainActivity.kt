package com.sha.mymoney

import android.app.*
import android.os.Bundle
import android.graphics.*
import android.view.*
import android.widget.*
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.*

private const val BG = 0xFF242820.toInt()
private const val PANEL = 0xFF30362D.toInt()
private const val GOLD = 0xFFE7C34F.toInt()
private const val MUTED = 0xFFB8BBAE.toInt()
private val COLORS = intArrayOf(0xFFE7C34F.toInt(),0xFF56B6A5.toInt(),0xFF6D91D5.toInt(),0xFFE18472.toInt(),0xFFB28AD5.toInt(),0xFF91B85A.toInt())

data class Tx(val id:Long,val title:String,val amount:Double,val kind:String,val category:String,val date:String)
class Store(c:android.content.Context):SQLiteOpenHelper(c,"mymoney.db",null,1) {
 override fun onCreate(db:SQLiteDatabase){ db.execSQL("CREATE TABLE tx(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT,amount REAL,kind TEXT,category TEXT,date TEXT)") }
 override fun onUpgrade(db:SQLiteDatabase,o:Int,n:Int){}
 fun add(t:String,a:Double,k:String,c:String){writableDatabase.execSQL("INSERT INTO tx(title,amount,kind,category,date) VALUES(?,?,?,?,?)",arrayOf(t,a,k,c,SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date())))}
 fun all():List<Tx>{val out=mutableListOf<Tx>();readableDatabase.rawQuery("SELECT id,title,amount,kind,category,date FROM tx ORDER BY id DESC",null).use{while(it.moveToNext())out+=Tx(it.getLong(0),it.getString(1),it.getDouble(2),it.getString(3),it.getString(4),it.getString(5))};return out}
 fun remove(id:Long){writableDatabase.delete("tx","id=?",arrayOf(id.toString()))}
}
class MainActivity:Activity(){
 private lateinit var store:Store
 private lateinit var root:LinearLayout
 private var tab=0
 private var period="Month"
 private var chosenCategory:String?=null
 private val cats=listOf("Food","Transport","Shopping","Bills","Health","Entertainment","Salary","Other")
 private fun dp(v:Int)= (v*resources.displayMetrics.density).toInt()
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);store=Store(this);window.statusBarColor=BG;window.navigationBarColor=BG;show()}
 private fun text(s:String,size:Float=15f,color:Int=Color.WHITE):TextView=TextView(this).apply{text=s;textSize=size;setTextColor(color);setPadding(dp(12),dp(9),dp(12),dp(9))}
 private fun button(s:String,action:()->Unit):Button=Button(this).apply{text=s;setTextColor(Color.BLACK);setBackgroundColor(GOLD);setOnClickListener{action()}}
 private fun panel():LinearLayout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(8),dp(10),dp(8));setBackgroundColor(PANEL)}
 private fun show(){root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(BG);setPadding(dp(12),dp(10),dp(12),dp(4))};val head=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
 head.addView(text("MyMoney",25f,GOLD),LinearLayout.LayoutParams(0,-2,1f));head.addView(text("PERSONAL FINANCE",10f,MUTED));root.addView(head)
 val body=ScrollView(this);val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};body.addView(content);root.addView(body,LinearLayout.LayoutParams(-1,0,1f))
 when(tab){0->records(content);1->analysis(content);2->budgets(content);3->accounts(content);4->categories(content)}
 val nav=LinearLayout(this).apply{setBackgroundColor(PANEL);gravity=Gravity.CENTER};listOf("Records","Analysis","Budgets","Accounts","Categories").forEachIndexed{i,n->val b=TextView(this).apply{text=n;textSize=10f;gravity=Gravity.CENTER;setTextColor(if(tab==i)GOLD else MUTED);setPadding(dp(5),dp(15),dp(5),dp(15));setOnClickListener{tab=i;chosenCategory=null;show()}};nav.addView(b,LinearLayout.LayoutParams(0,-2,1f))};root.addView(nav);setContentView(root)}
 private fun money(v:Double)="₹"+String.format(Locale("en","IN"),"%.2f",v)
 private fun totals(list:List<Tx>,kind:String)=list.filter{it.kind==kind}.sumOf{it.amount}
 private fun records(c:LinearLayout){val data=store.all();val card=panel();card.addView(text("TOTAL BALANCE",12f,MUTED));card.addView(text(money(totals(data,"Income")-totals(data,"Expense")),30f,GOLD));val row=LinearLayout(this);row.addView(text("Income  "+money(totals(data,"Income")),14f,0xFF72D6A8.toInt()),LinearLayout.LayoutParams(0,-2,1f));row.addView(text("Expense  "+money(totals(data,"Expense")),14f,0xFFE58B7D.toInt()));card.addView(row);c.addView(card);c.addView(text("Recent transactions",19f,GOLD));c.addView(button("+ Add transaction"){addDialog()});if(data.isEmpty())c.addView(text("No entries yet. Add your first income or expense."));data.forEach{t->val p=panel();p.addView(text(t.title+"  •  "+t.category));p.addView(text(t.date+"     "+(if(t.kind=="Income")"+" else "−")+money(t.amount),14f,if(t.kind=="Income")0xFF72D6A8.toInt() else 0xFFE58B7D.toInt()));p.setOnLongClickListener{AlertDialog.Builder(this).setMessage("Delete this transaction?").setPositiveButton("Delete"){_,_->store.remove(t.id);show()}.setNegativeButton("Cancel",null).show();true};c.addView(p)}}
 private fun addDialog(){val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(30,10,30,0)};val title=EditText(this).apply{hint="Description"};val amount=EditText(this).apply{hint="Amount";inputType=8194};val kind=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("Expense","Income"))};val cat=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,cats)};box.addView(title);box.addView(amount);box.addView(kind);box.addView(cat);AlertDialog.Builder(this).setTitle("New transaction").setView(box).setPositiveButton("Save"){_,_->val a=amount.text.toString().toDoubleOrNull();if(a!=null&&a>0){store.add(title.text.toString().ifBlank{cat.selectedItem.toString()},a,kind.selectedItem.toString(),cat.selectedItem.toString());show()}else Toast.makeText(this,"Enter a valid amount",Toast.LENGTH_SHORT).show()}.setNegativeButton("Cancel",null).show()}
 private fun filtered():List<Tx>{val cal=Calendar.getInstance();return store.all().filter{t->try{val d=SimpleDateFormat("yyyy-MM-dd",Locale.US).parse(t.date)!!;val x=Calendar.getInstance().apply{time=d};when(period){"Day"->cal.get(Calendar.YEAR)==x.get(Calendar.YEAR)&&cal.get(Calendar.DAY_OF_YEAR)==x.get(Calendar.DAY_OF_YEAR);"Week"->cal.get(Calendar.YEAR)==x.get(Calendar.YEAR)&&cal.get(Calendar.WEEK_OF_YEAR)==x.get(Calendar.WEEK_OF_YEAR);"Year"->cal.get(Calendar.YEAR)==x.get(Calendar.YEAR);else->cal.get(Calendar.YEAR)==x.get(Calendar.YEAR)&&cal.get(Calendar.MONTH)==x.get(Calendar.MONTH)}}catch(_:Exception){false}}}
 private fun analysis(c:LinearLayout){c.addView(text("Spending analysis",21f,GOLD));val r=LinearLayout(this);listOf("Day","Week","Month","Year").forEach{r.addView(button(it){period=it;show()},LinearLayout.LayoutParams(0,-2,1f))};c.addView(r);val data=filtered().filter{it.kind=="Expense"};val total=data.sumOf{it.amount};c.addView(text("Total expenses  "+money(total),22f));val grouped=data.groupBy{it.category}.mapValues{e->e.value.sumOf{it.amount}};if(grouped.isEmpty())c.addView(text("No expenses for this period."))else{val chart=PieView(this,grouped);c.addView(chart,LinearLayout.LayoutParams(-1,dp(230)));grouped.entries.sortedByDescending{it.value}.forEachIndexed{i,e->val p=panel();p.addView(text("●  "+e.key+"     "+money(e.value)+"  ("+(if(total>0)(e.value*100/total).toInt() else 0)+"%)",15f,COLORS[i%COLORS.size]));p.setOnClickListener{chosenCategory=e.key;showCategoryTransactions(c,e.key)};c.addView(p)}}}
 private fun showCategoryTransactions(c:LinearLayout,category:String){c.addView(text("Selected: $category",18f,GOLD));store.all().filter{it.category==category&&it.kind=="Expense"}.forEach{c.addView(text(it.title+"  "+money(it.amount)+"  "+it.date))}}
 private fun budgets(c:LinearLayout){c.addView(text("Budgets",22f,GOLD));c.addView(text("Budget planning is coming in the next test update. Your transactions are already stored locally."))}
 private fun accounts(c:LinearLayout){c.addView(text("Accounts",22f,GOLD));c.addView(text("Default account: Cash"));c.addView(text("Offline storage: on this device"))}
 private fun categories(c:LinearLayout){c.addView(text("Categories",22f,GOLD));cats.forEach{c.addView(text("•  $it"))};c.addView(text("Custom category management will be added after the first test build.",13f,MUTED))}
}
class PieView(ctx:android.content.Context,private val values:Map<String,Double>):View(ctx){
 private val paint=Paint(3)
 override fun onDraw(canvas:Canvas){super.onDraw(canvas);val total=values.values.sum();if(total<=0)return;val size=minOf(width,height).toFloat();val rect=RectF((width-size)/2+dp(8),(height-size)/2+dp(8),(width+size)/2-dp(8),(height+size)/2-dp(8));var start=-90f;values.values.forEachIndexed{i,v->paint.color=COLORS[i%COLORS.size];val sweep=(v/total*360).toFloat();canvas.drawArc(rect,start,sweep,true,paint);start+=sweep}}
 private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
}
