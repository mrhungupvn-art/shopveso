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
        val token = session.token()
        if (!token.isNullOrBlank()) {
            api = Api(BuildConfig.API_BASE_URL, session.kcnId() ?: DEFAULT_KCN_ID, token)
            showApp()
        } else {
            showLogin()
        }
    }

    private fun shell() {
        box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }
        setContentView(ScrollView(this).apply { addView(box) })
    }

    private fun showLogin() {
        shell()
        box.addView(title("🏪 VESO SHOP" + System.lineSeparator() + "Quản lý vé và đơn hàng"))
        val u = field("Tài khoản")
        val p = field("Mật khẩu", true)
        box.addView(u)
        box.addView(p)
        val b = Button(this).apply { text = "ĐĂNG NHẬP" }
        status = TextView(this)
        box.addView(b)
        box.addView(status)
        b.setOnClickListener {
            async {
                val username = u.text.toString().trim()
                val password = p.text.toString()
                if (username.isBlank() || password.isBlank()) throw ApiException("Vui lòng nhập tài khoản và mật khẩu.")

                val loginApi = Api(BuildConfig.API_BASE_URL, session.kcnId() ?: DEFAULT_KCN_ID)
                val j = loginApi.call(
                    "partner_login",
                    JSONObject()
                        .put("username", username)
                        .put("password", password)
                        .put("device", Build.MODEL)
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
        shell()
        box.addView(title("🏪 VESO SHOP"))
        status = TextView(this)
        box.addView(status)

        val row = LinearLayout(this)
        val products = Button(this).apply { text = "VÉ" }
        val orders = Button(this).apply { text = "ĐƠN HÀNG" }
        val publish = Button(this).apply { text = "➕ THÊM VÉ" }
        val logout = Button(this).apply { text = "THOÁT" }
        row.addView(products)
        row.addView(orders)
        row.addView(publish)
        row.addView(logout)
        box.addView(row)

        products.setOnClickListener { loadProducts() }
        orders.setOnClickListener { loadOrders() }
        publish.setOnClickListener { publishDialog() }
        logout.setOnClickListener {
            thread {
                runCatching { api.call("partner_logout", JSONObject()) }
                ui {
                    stopService(Intent(this, OrderAlertService::class.java))
                    session.clear()
                    showLogin()
                }
            }
        }

        startService(Intent(this, OrderAlertService::class.java))
        loadProducts()
    }

    private fun loadProducts() {
        async {
            val data = api.call("partner_food_list").optJSONObject("data")
            val foods = data?.optJSONArray("foods") ?: org.json.JSONArray()
            val permissions = data?.optJSONObject("permissions")
            ui {
                clearContent()
                if (foods.length() == 0) {
                    addText("Chưa có vé trong tiệm.")
                } else {
                    for (i in 0 until foods.length()) {
                        val o = foods.getJSONObject(i)
                        val price = o.optLong("price")
                        val statusText = when (o.optString("shop_price_status")) {
                            "pending" -> "Đang chờ Admin duyệt giá"
                            "approved", "auto_approved" -> "Đã duyệt giá"
                            "rejected" -> "Giá bị từ chối"
                            else -> if (price > 0) "Đã có giá" else "Chưa có giá"
                        }
                        addText(
                            "🎟️ ${o.optString("name")}" + System.lineSeparator() +
                                "Giá: ${if (price > 0) money(price) else "Chờ Admin quyết định"}" + System.lineSeparator() +
                                "Tồn: ${o.optInt("stock")}" + System.lineSeparator() + statusText
                        )
                    }
                    if (permissions != null) {
                        status.text = "Đã tải ${foods.length()} vé • Hôm nay ${permissions.optInt("posts_today")}/${permissions.optInt("daily_post_limit")} lượt đăng"
                    }
                }
            }
        }
    }

    private fun loadOrders() {
        async {
            val data = api.call("partner_pickups").optJSONObject("data")
            val a = data?.optJSONArray("pickups") ?: org.json.JSONArray()
            ui {
                clearContent()
                if (a.length() == 0) {
                    addText("Chưa có đơn hàng.")
                } else {
                    for (i in 0 until a.length()) {
                        val o = a.getJSONObject(i)
                        val pickupId = o.optInt("pickup_id", 0)
                        val itemText = o.optJSONArray("items")?.let { items ->
                            (0 until items.length()).mapNotNull { idx ->
                                items.optJSONObject(idx)?.let { item -> "${item.optString("name")} x${item.optInt("qty")}" }
                            }.joinToString(" • ")
                        }.orEmpty()
                        val b = Button(this).apply {
                            text = buildString {
                                append(o.optString("code"))
                                append(System.lineSeparator())
                                append(o.optString("customer"))
                                append(" • ")
                                append(o.optString("phone"))
                                append(System.lineSeparator())
                                if (itemText.isNotBlank()) {
                                    append(itemText)
                                    append(System.lineSeparator())
                                }
                                append("Thanh toán: ")
                                append(o.optString("payment_status"))
                                append(" • ")
                                append(o.optString("pickup_status"))
                            }
                        }
                        b.setOnClickListener { orderDialog(o) }
                        box.addView(b)
                    }
                }
            }
        }
    }

    private fun orderDialog(o: JSONObject) {
        val pickupId = o.optInt("pickup_id", 0)
        if (pickupId <= 0) return
        val statusNow = o.optString("pickup_status")
        val buttons = mutableListOf<String>()
        if (statusNow == "pending") buttons.add("XÁC NHẬN ĐƠN")
        if (statusNow == "confirmed" || statusNow == "preparing") buttons.add("BÁO SẴN SÀNG")
        buttons.add("ĐÓNG")

        AlertDialog.Builder(this)
            .setTitle("Đơn ${o.optString("code")}")
            .setMessage(
                "Khách: ${o.optString("customer")}" + System.lineSeparator() +
                    "SĐT: ${o.optString("phone")}" + System.lineSeparator() +
                    "Địa chỉ: ${o.optString("address")}" + System.lineSeparator() +
                    "Trạng thái Shop: $statusNow"
            )
            .setItems(buttons.toTypedArray()) { dialog, which ->
                val action = buttons[which]
                when (action) {
                    "XÁC NHẬN ĐƠN" -> async {
                        api.call("partner_confirm_pickup", JSONObject().put("pickup_id", pickupId))
                        ui { loadOrders() }
                    }
                    "BÁO SẴN SÀNG" -> async {
                        api.call("partner_mark_ready", JSONObject().put("pickup_id", pickupId))
                        ui { loadOrders() }
                    }
                    else -> dialog.dismiss()
                }
            }
            .show()
    }

    private fun publishDialog() {
        val name = field("Tên vé")
        val qty = field("Số lượng", false).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("1")
        }
        val proposed = field("Giá đề xuất (có thể để trống)", false).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 4, 8, 4)
            addView(name)
            addView(qty)
            addView(proposed)
        }

        AlertDialog.Builder(this)
            .setTitle("➕ Thêm vé")
            .setView(form)
            .setPositiveButton("GỬI ADMIN") { _, _ ->
                async {
                    val ticketName = name.text.toString().trim()
                    val quantity = qty.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
                    val proposedPrice = proposed.text.toString().toLongOrNull()?.coerceAtLeast(0) ?: 0L
                    if (ticketName.isBlank()) throw ApiException("Vui lòng nhập tên vé.")
                    if (quantity <= 0) throw ApiException("Số lượng vé phải lớn hơn 0.")
                    api.upload(
                        "partner_food_create",
                        mapOf(
                            "name" to ticketName,
                            "stock" to quantity.toString(),
                            "proposed_price" to proposedPrice.toString()
                        )
                    )
                    ui { loadProducts() }
                }
            }
            .setNegativeButton("HỦY", null)
            .show()
    }

    private fun clearContent() {
        while (box.childCount > 2) box.removeViewAt(2)
    }

    private fun addText(s: String) {
        box.addView(TextView(this).apply {
            text = s
            textSize = 16f
            setPadding(0, 14, 0, 14)
        })
    }

    private fun title(s: String) = TextView(this).apply {
        text = s
        textSize = 27f
        setTypeface(null, Typeface.BOLD)
        setPadding(0, 0, 0, 12)
    }

    private fun field(h: String, password: Boolean = false) = EditText(this).apply {
        hint = h
        if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    private fun money(n: Long) = "%,d đ".format(n).replace(',', '.')
    private fun ui(f: () -> Unit) = runOnUiThread(f)

    private fun async(f: () -> Unit) {
        thread {
            try {
                f()
            } catch (e: Exception) {
                ui { status.text = e.message ?: "Có lỗi xảy ra" }
            }
        }
    }

    companion object {
        private const val DEFAULT_KCN_ID = 1
    }
}
