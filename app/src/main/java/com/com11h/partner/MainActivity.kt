package com.com11h.partner

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.graphics.Typeface
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.json.JSONObject
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var api: Api
    private lateinit var session: SecureSession
    private lateinit var box: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = SecureSession(this)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        session.token()?.let { api = Api(BuildConfig.API_BASE_URL, 1, it); showApp() } ?: showLogin()
    }

    private fun shell() {
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 20, 20, 20) }
        setContentView(ScrollView(this).apply { addView(box) })
    }

    private fun showLogin() {
        shell(); box.addView(title("🏪 VESO SHOP\nQuản lý vé và đơn hàng"))
        val u = field("Tài khoản"); val p = field("Mật khẩu", true); box.addView(u); box.addView(p)
        val b = Button(this).apply { text = "ĐĂNG NHẬP" }; status = TextView(this); box.addView(b); box.addView(status)
        b.setOnClickListener {
            async {
                val username = u.text.toString().trim()
                val password = p.text.toString()
                val j = Api(BuildConfig.API_BASE_URL, session.kcnId() ?: DEFAULT_KCN_ID).call(
                    "partner_login",
                    JSONObject().put("username", username).put("password", password).put("device", Build.MODEL)
                )
                val data = j.optJSONObject("data")
                    ?: throw ApiException("Máy chủ trả về dữ liệu đăng nhập không hợp lệ.")
                val token = data.optString("token").trim()
                if (token.isBlank()) throw ApiException("Máy chủ không trả về token đăng nhập.")
                val partner = data.optJSONObject("partner")
                    ?: throw ApiException("Máy chủ không trả về thông tin Shop.")
                val kcnId = session.kcnId() ?: DEFAULT_KCN_ID
                session.save(
                    token,
                    kcnId,
                    partner.optString("store_name", partner.optString("shop_name")),
                    partner.optInt("store_id", partner.optInt("shop_id")),
                    partner.optString("category")
                )
                api = Api(BuildConfig.API_BASE_URL, kcnId, token)
                ui { showApp() }
            }
        }
    }

    private fun showApp() {
        shell(); box.addView(title("🏪 VESO SHOP")); status=TextView(this); box.addView(status)
        val row=LinearLayout(this); val products=Button(this).apply{text="VÉ"}; val orders=Button(this).apply{text="ĐƠN HÀNG"}; val publish=Button(this).apply{text="➕ ĐĂNG VÉ"}; val logout=Button(this).apply{text="THOÁT"}
        row.addView(products); row.addView(orders); row.addView(publish); row.addView(logout); box.addView(row)
        products.setOnClickListener { loadProducts() }; orders.setOnClickListener { loadOrders() }; publish.setOnClickListener { publishDialog() }; logout.setOnClickListener { stopService(Intent(this, OrderAlertService::class.java)); session.clear(); showLogin() }
        startService(Intent(this, OrderAlertService::class.java)); loadProducts()
    }

    private fun loadProducts() { async { val a=api.call("partner_products").optJSONObject("data")?.optJSONArray("products") ?: org.json.JSONArray(); ui { clearContent(); if(a.length()==0) addText("Chưa đăng vé.") else for(i in 0 until a.length()){val o=a.getJSONObject(i);addText("${o.optString("provider_name")} • ${o.optString("draw_date")}\nGiá cố định: ${money(o.optInt("face_value"))}\nĐang có: ${o.optInt("quantity")-o.optInt("sold_quantity")} vé")} } } }

    private fun loadOrders() { async { val a=api.call("partner_orders").optJSONObject("data")?.optJSONArray("orders") ?: org.json.JSONArray(); ui { clearContent(); if(a.length()==0) addText("Chưa có đơn.") else for(i in 0 until a.length()){val o=a.getJSONObject(i);val b=Button(this).apply{text="${o.optString("code")}\n${o.optString("customer_name")} • ${o.optString("customer_phone")}\n${o.optString("status")} • ${money(o.optInt("total"))}"}; b.setOnClickListener{ if(o.optString("status")=="PENDING") confirmOrder(o.getInt("id")) }; box.addView(b) } } } }

    private fun confirmOrder(id:Int) { AlertDialog.Builder(this).setTitle("Xác nhận đơn").setMessage("Xác nhận Shop đã nhận và chuẩn bị đơn này?").setPositiveButton("XÁC NHẬN"){_,_->async{api.call("partner_confirm",JSONObject().put("order_id",id));ui{loadOrders()}}}.setNegativeButton("HỦY",null).show() }

    private fun publishDialog() { async { val a=api.call("draws").optJSONObject("data")?.optJSONArray("draws") ?: org.json.JSONArray(); ui { if(a.length()==0){status.text="Chưa có kỳ xổ mở";return@ui}; val labels=Array(a.length()){i->val o=a.getJSONObject(i);"${o.optString("provider_name")} • ${o.optString("draw_date")} • ${money(o.optInt("face_value"))}"}; AlertDialog.Builder(this).setTitle("Chọn kỳ xổ").setSingleChoiceItems(labels,-1){d,which->d.dismiss(); quantityDialog(a.getJSONObject(which).getInt("id"))}.show() } } }

    private fun quantityDialog(drawId:Int) { val q=EditText(this).apply{hint="Số lượng";inputType=InputType.TYPE_CLASS_NUMBER;setText("1")}; AlertDialog.Builder(this).setTitle("Số lượng vé").setView(q).setPositiveButton("ĐĂNG"){_,_->async{val n=q.text.toString().toIntOrNull()?.coerceAtLeast(1)?:throw RuntimeException("Số lượng không hợp lệ");api.call("partner_publish",JSONObject().put("draw_id",drawId).put("quantity",n));ui{loadProducts()}}}.setNegativeButton("HỦY",null).show() }

    private fun clearContent(){ while(box.childCount>2) box.removeViewAt(2) }
    private fun addText(s:String){box.addView(TextView(this).apply{text=s;textSize=16f;setPadding(0,14,0,14)})}
    private fun title(s:String)=TextView(this).apply{text=s;textSize=27f;setTypeface(null,Typeface.BOLD);setPadding(0,0,0,12)}
    private fun field(h:String,p:Boolean=false)=EditText(this).apply{hint=h;if(p)inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD}
    private fun money(n:Int)="%,d đ".format(n).replace(',','.')
    private fun ui(f:()->Unit)=runOnUiThread(f)
    private fun async(f:()->Unit){thread{try{f()}catch(e:Exception){ui{status.text=e.message ?: "Có lỗi xảy ra"}}}}

    companion object {
        private const val DEFAULT_KCN_ID = 1
    }
}
