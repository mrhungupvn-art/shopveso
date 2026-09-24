package com.com11h.partner

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var api: Api
    private lateinit var session: SecureSession
    private lateinit var rootBox: LinearLayout
    private lateinit var contentBox: LinearLayout
    private lateinit var status: TextView
    private lateinit var topStatus: TextView

    private var selectedImageUri: Uri? = null
    private var pendingImageCallback: ((Uri) -> Unit)? = null

    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedImageUri = uri
            pendingImageCallback?.invoke(uri)
        }
        pendingImageCallback = null
    }

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
        rootBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        setContentView(ScrollView(this).apply { addView(rootBox) })
    }

    private fun showLogin() {
        shell()
        rootBox.addView(title("🏪 VESO SHOP"))
        rootBox.addView(addTextView("Quản lý vé số • đơn hàng • kho vé • doanh số"))
        val u = field("Tài khoản")
        val p = field("Mật khẩu", true)
        rootBox.addView(u)
        rootBox.addView(p)
        val b = Button(this).apply { text = "ĐĂNG NHẬP" }
        status = TextView(this)
        rootBox.addView(b)
        rootBox.addView(status)
        b.setOnClickListener {
            async {
                val username = u.text.toString().trim()
                val password = p.text.toString()
                if (username.isBlank() || password.isBlank()) throw ApiException("Vui lòng nhập tài khoản và mật khẩu.")
                val loginApi = Api(BuildConfig.API_BASE_URL, session.kcnId() ?: DEFAULT_KCN_ID)
                val j = loginApi.call(
                    "partner_login",
                    JSONObject().put("username", username).put("password", password).put("device", Build.MODEL)
                )
                val data = j.optJSONObject("data") ?: throw ApiException("Máy chủ trả về dữ liệu đăng nhập không hợp lệ.")
                val token = data.optString("token").trim()
                if (token.isBlank()) throw ApiException("Máy chủ không trả về token đăng nhập.")
                val partner = data.optJSONObject("partner") ?: throw ApiException("Máy chủ không trả về thông tin Shop.")
                val kcnId = session.kcnId() ?: DEFAULT_KCN_ID
                session.save(
                    token,
                    kcnId,
                    partner.optString("store_name", partner.optString("shop_name")),
                    partner.optInt("store_id", partner.optInt("shop_id")),
                    partner.optString("category")
                )
                api = Api(BuildConfig.API_BASE_URL, kcnId, token)
                val contractRequired = data.optBoolean("contract_required", false)
                ui { if (contractRequired) showContractScreen() else showApp() }
            }
        }
    }

    private fun showApp() {
        shell()
        rootBox.addView(title("🏪 VESO SHOP"))
        topStatus = TextView(this).apply { setPadding(0, 0, 0, dp(10)); textSize = 14f }
        rootBox.addView(topStatus)
        status = TextView(this).apply { textSize = 14f; setPadding(0, 0, 0, dp(8)) }
        rootBox.addView(status)

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        row1.addView(menuButton("🏠 Tổng quan") { loadDashboard() }, weightParams())
        row1.addView(menuButton("🆕 Đơn mới") { loadOrders("pending") }, weightParams())
        row1.addView(menuButton("🍳 Đang làm") { loadOrders("working") }, weightParams())
        rootBox.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        row2.addView(menuButton("✅ Lịch sử") { loadOrders("history") }, weightParams())
        row2.addView(menuButton("🎟️ Kho vé") { loadTickets() }, weightParams())
        row2.addView(menuButton("📊 Thống kê") { loadStats(7) }, weightParams())
        rootBox.addView(row2)

        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        row3.addView(menuButton("💰 Đối soát") { loadLedger() }, weightParams())
        row3.addView(menuButton("👤 Tài khoản") { loadAccount() }, weightParams())
        row3.addView(menuButton("↻ Đồng bộ") { loadDashboard() }, weightParams())
        rootBox.addView(row3)

        rootBox.addView(divider())
        contentBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rootBox.addView(contentBox)

        startService(Intent(this, OrderAlertService::class.java))
        loadDashboard()
    }

    private fun loadDashboard() {
        async {
            val data = api.call("partner_dashboard").optJSONObject("data") ?: JSONObject()
            ui {
                clearContent()
                val partner = data.optJSONObject("partner") ?: JSONObject()
                val sales = data.optJSONObject("today") ?: JSONObject()
                val inv = data.optJSONObject("inventory") ?: JSONObject()
                val pickup = data.optJSONObject("pickups") ?: JSONObject()
                val settlement = data.optJSONObject("settlement") ?: JSONObject()
                topStatus.text = "🏪 ${partner.optString("store_name", "VESO SHOP")} • ${partner.optString("category", "Vé số")}"
                status.text = "Hôm nay: ${sales.optInt("ticket_count")} vé bán • ${money(sales.optLong("revenue"))} doanh thu"

                addSectionTitle("📌 TỔNG QUAN HÔM NAY")
                val grid = verticalGrid()
                grid.addView(metric("🎟️", "Vé đã bán", sales.optInt("ticket_count").toString()))
                grid.addView(metric("🧾", "Đơn đã thanh toán", sales.optInt("order_count").toString()))
                grid.addView(metric("💵", "Doanh thu", money(sales.optLong("revenue"))))
                grid.addView(metric("📦", "Tồn kho", inv.optInt("stock_total").toString()))
                grid.addView(metric("🔔", "Đơn chờ", pickup.optInt("pending").toString()))
                grid.addView(metric("🍳", "Đang làm", pickup.optInt("working").toString()))
                contentBox.addView(grid)

                addSectionTitle("💰 ĐỐI SOÁT")
                addText("Đang chờ thanh toán: ${money(settlement.optLong("pending"))}")
                addText("Đã thanh toán: ${money(settlement.optLong("paid"))}")

                addSectionTitle("⚡ THAO TÁC NHANH")
                val quick = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                quick.addView(actionButton("➕ Upload vé") { publishDialog() }, weightParams())
                quick.addView(actionButton("📊 Doanh số 30 ngày") { loadStats(30) }, weightParams())
                contentBox.addView(quick)
            }
        }
    }

    private fun loadTickets() {
        async {
            val data = api.call("partner_food_list").optJSONObject("data") ?: JSONObject()
            val foods = data.optJSONArray("foods") ?: JSONArray()
            val permissions = data.optJSONObject("permissions") ?: JSONObject()
            ui {
                clearContent()
                val postsToday = permissions.optInt("posts_today", 0)
                val limit = permissions.optInt("daily_post_limit", 0)
                addSectionTitle("🎟️ KHO VÉ SỐ")
                addText("Đã đăng hôm nay: $postsToday/${if (limit > 0) limit else "∞"}")
                contentBox.addView(actionButton("➕ UPLOAD VÉ SỐ MỚI") { publishDialog() })
                addText("Chạm vào một vé để sửa tồn kho/ảnh hoặc gửi giá đề xuất.")

                if (foods.length() == 0) {
                    addText("Chưa có vé trong kho.")
                } else {
                    for (i in 0 until foods.length()) {
                        val o = foods.getJSONObject(i)
                        ticketCard(o)
                    }
                }
                status.text = "Đã tải ${foods.length()} loại vé • Hôm nay $postsToday/${if (limit > 0) limit else "∞"} lượt đăng"
            }
        }
    }

    private fun ticketCard(o: JSONObject) {
        val card = cardBox()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val image = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(78), dp(78)).apply { rightMargin = dp(10) }
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val url = o.optString("image_url")
        if (url.isNotBlank()) loadRemoteImage(url, image)
        row.addView(image)

        val textBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        val price = o.optLong("price")
        val statusText = when (o.optString("shop_price_status")) {
            "pending" -> "⏳ Chờ duyệt giá"
            "approved", "auto_approved" -> "✅ Đã duyệt giá"
            "rejected" -> "❌ Giá bị từ chối"
            else -> if (price > 0) "✅ Có giá" else "⏳ Chờ Admin"
        }
        textBox.addView(boldText("🎟️ ${o.optString("name")}"))
        textBox.addView(addTextView("Giá bán: ${if (price > 0) money(price) else "Chờ Admin quyết định"}"))
        textBox.addView(addTextView("Tồn kho: ${o.optInt("stock")}"))
        textBox.addView(addTextView(statusText))
        row.addView(textBox)
        card.addView(row)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(actionButton("✏️ Sửa kho") { editTicketDialog(o) }, weightParams())
        buttons.addView(actionButton("💲 Gửi giá") { submitPriceDialog(o) }, weightParams())
        card.addView(buttons)
        contentBox.addView(card)
    }

    private fun loadOrders(filter: String) {
        async {
            val data = api.call("partner_pickups").optJSONObject("data") ?: JSONObject()
            val all = data.optJSONArray("pickups") ?: JSONArray()
            ui {
                clearContent()
                addSectionTitle(when (filter) {
                    "pending" -> "🆕 ĐƠN MỚI"
                    "working" -> "🍳 ĐANG CHUẨN BỊ"
                    else -> "✅ LỊCH SỬ ĐƠN"
                })
                var shown = 0
                for (i in 0 until all.length()) {
                    val o = all.getJSONObject(i)
                    val s = o.optString("pickup_status")
                    val match = when (filter) {
                        "pending" -> s == "pending"
                        "working" -> s == "confirmed" || s == "preparing"
                        else -> s == "ready" || s == "picked_up" || s == "rejected"
                    }
                    if (!match) continue
                    shown++
                    orderCard(o)
                }
                if (shown == 0) addText("Không có đơn trong nhóm này.")
                status.text = "Tổng ${shown} đơn đang hiển thị"
            }
        }
    }

    private fun orderCard(o: JSONObject) {
        val card = cardBox()
        val title = "🧾 ${o.optString("code")} • ${o.optString("pickup_status")}"
        card.addView(boldText(title))
        card.addView(addTextView("Thanh toán: ${o.optString("payment_status")}"))
        val items = o.optJSONArray("items") ?: JSONArray()
        if (items.length() > 0) {
            val lines = StringBuilder()
            for (i in 0 until items.length()) {
                val x = items.optJSONObject(i) ?: continue
                lines.append("• ${x.optString("name")} × ${x.optInt("qty")}").append('\n')
            }
            card.addView(addTextView(lines.toString().trimEnd()))
        }
        if (o.optString("note").isNotBlank()) card.addView(addTextView("Ghi chú: ${o.optString("note")}"))
        val b = actionButton("Xem / xử lý đơn") { orderDialog(o) }
        card.addView(b)
        contentBox.addView(card)
    }

    private fun orderDialog(o: JSONObject) {
        val pickupId = o.optInt("pickup_id", 0)
        if (pickupId <= 0) return
        val statusNow = o.optString("pickup_status")
        val lines = StringBuilder()
        lines.append("Mã đơn: ${o.optString("code")}").append('\n')
        lines.append("Thanh toán: ${o.optString("payment_status")}").append('\n')
        lines.append("Trạng thái Shop: $statusNow").append('\n\n')
        val items = o.optJSONArray("items") ?: JSONArray()
        for (i in 0 until items.length()) {
            val x = items.optJSONObject(i) ?: continue
            lines.append("• ${x.optString("name")} × ${x.optInt("qty")}").append('\n')
        }
        if (o.optString("note").isNotBlank()) lines.append("\nGhi chú: ${o.optString("note")}")

        val buttons = mutableListOf<String>()
        if (statusNow == "pending") buttons.add("XÁC NHẬN ĐƠN")
        if (statusNow == "confirmed" || statusNow == "preparing") buttons.add("BÁO SẴN SÀNG")
        if (statusNow == "pending") buttons.add("TỪ CHỐI")
        buttons.add("ĐÓNG")

        AlertDialog.Builder(this)
            .setTitle("Đơn ${o.optString("code")}")
            .setMessage(lines.toString().trim())
            .setItems(buttons.toTypedArray()) { dialog, which ->
                when (buttons[which]) {
                    "XÁC NHẬN ĐƠN" -> async { api.call("partner_confirm_pickup", JSONObject().put("pickup_id", pickupId)); ui { loadOrders("pending") } }
                    "BÁO SẴN SÀNG" -> async { api.call("partner_mark_ready", JSONObject().put("pickup_id", pickupId)); ui { loadOrders("working") } }
                    "TỪ CHỐI" -> rejectDialog(pickupId)
                    else -> dialog.dismiss()
                }
            }
            .show()
    }

    private fun rejectDialog(pickupId: Int) {
        val reason = field("Lý do từ chối")
        AlertDialog.Builder(this)
            .setTitle("Từ chối đơn")
            .setView(reason)
            .setPositiveButton("GỬI") { _, _ ->
                async {
                    api.call("partner_reject_pickup", JSONObject().put("pickup_id", pickupId).put("reason", reason.text.toString().trim()))
                    ui { loadOrders("pending") }
                }
            }
            .setNegativeButton("HỦY", null)
            .show()
    }

    private fun publishDialog() {
        selectedImageUri = null
        val name = field("Tên vé số, ví dụ: Vé Cần Thơ")
        val qty = field("Số lượng tồn", false).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText("1") }
        val proposed = field("Giá đề xuất", false).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val imageStatus = addTextView("Chưa chọn ảnh")
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            addView(name); addView(qty); addView(proposed)
            addView(actionButton("🖼️ CHỌN / UPLOAD ẢNH VÉ") { chooseImage { imageStatus.text = "Đã chọn ảnh: ${displayName(it)}" } })
            addView(imageStatus)
        }
        AlertDialog.Builder(this)
            .setTitle("➕ UPLOAD VÉ SỐ")
            .setView(form)
            .setPositiveButton("GỬI ADMIN") { _, _ ->
                async {
                    val ticketName = name.text.toString().trim()
                    val quantity = qty.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
                    val proposedPrice = proposed.text.toString().toLongOrNull()?.coerceAtLeast(0) ?: 0L
                    if (ticketName.isBlank()) throw ApiException("Vui lòng nhập tên vé.")
                    if (quantity <= 0) throw ApiException("Số lượng vé phải lớn hơn 0.")
                    val image = selectedImageUri?.let { readUploadFile(it) }
                    api.upload("partner_food_create", mapOf("name" to ticketName, "stock" to quantity.toString(), "proposed_price" to proposedPrice.toString()), image)
                    ui { loadTickets() }
                }
            }
            .setNegativeButton("HỦY", null)
            .show()
    }

    private fun editTicketDialog(o: JSONObject) {
        selectedImageUri = null
        val qty = field("Số lượng tồn", false).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(o.optInt("stock").toString()) }
        val imageStatus = addTextView("Giữ nguyên ảnh hiện tại")
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(4))
            addView(addTextView("🎟️ ${o.optString("name")}")); addView(qty)
            addView(actionButton("🖼️ ĐỔI ẢNH VÉ") { chooseImage { imageStatus.text = "Đã chọn ảnh mới: ${displayName(it)}" } })
            addView(imageStatus)
        }
        AlertDialog.Builder(this)
            .setTitle("✏️ Cập nhật kho vé")
            .setView(form)
            .setPositiveButton("LƯU") { _, _ ->
                async {
                    val stock = qty.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
                    val image = selectedImageUri?.let { readUploadFile(it) }
                    api.upload("partner_food_update", mapOf("food_id" to o.optInt("id").toString(), "stock" to stock.toString()), image)
                    ui { loadTickets() }
                }
            }
            .setNegativeButton("HỦY", null)
            .show()
    }

    private fun submitPriceDialog(o: JSONObject) {
        val price = field("Giá đề xuất", false).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(if (o.optLong("shop_proposed_price") > 0) o.optLong("shop_proposed_price").toString() else "") }
        AlertDialog.Builder(this)
            .setTitle("💲 Gửi giá: ${o.optString("name")}")
            .setView(price)
            .setPositiveButton("GỬI ADMIN") { _, _ ->
                async {
                    val value = price.text.toString().toLongOrNull()?.coerceAtLeast(0) ?: 0L
                    if (value <= 0) throw ApiException("Giá đề xuất phải lớn hơn 0.")
                    api.upload("partner_food_submit_price", mapOf("food_id" to o.optInt("id").toString(), "proposed_price" to value.toString()))
                    ui { loadTickets() }
                }
            }
            .setNegativeButton("HỦY", null)
            .show()
    }

    private fun loadStats(days: Int) {
        async {
            val data = api.call("partner_stats&days=$days").optJSONObject("data") ?: JSONObject()
            ui {
                clearContent()
                addSectionTitle("📊 THỐNG KÊ BÁN VÉ $days NGÀY")
                val s = data.optJSONObject("summary") ?: JSONObject()
                val inv = data.optJSONObject("inventory") ?: JSONObject()
                val settlement = data.optJSONObject("settlement") ?: JSONObject()
                val grid = verticalGrid()
                grid.addView(metric("🎟️", "Vé đã bán", s.optInt("ticket_count").toString()))
                grid.addView(metric("🧾", "Đơn", s.optInt("order_count").toString()))
                grid.addView(metric("💵", "Doanh thu", money(s.optLong("revenue"))))
                grid.addView(metric("📦", "Tồn kho", inv.optInt("stock_total").toString()))
                contentBox.addView(grid)
                addSectionTitle("📅 DOANH SỐ TỪNG NGÀY")
                val daily = data.optJSONArray("daily") ?: JSONArray()
                if (daily.length() == 0) addText("Chưa có dữ liệu bán hàng trong kỳ.")
                for (i in 0 until daily.length()) {
                    val d = daily.getJSONObject(i)
                    addText("${d.optString("date")} • ${d.optInt("ticket_count")} vé • ${money(d.optLong("revenue"))}")
                }
                addSectionTitle("🏆 VÉ BÁN NHIỀU")
                val top = data.optJSONArray("top_tickets") ?: JSONArray()
                if (top.length() == 0) addText("Chưa có dữ liệu.")
                for (i in 0 until top.length()) {
                    val t = top.getJSONObject(i)
                    addText("${i + 1}. ${t.optString("name")} • ${t.optInt("ticket_count")} vé • ${money(t.optLong("revenue"))}")
                }
                addSectionTitle("💰 ĐỐI SOÁT")
                addText("Chờ thanh toán: ${money(settlement.optLong("pending"))}")
                addText("Đã thanh toán: ${money(settlement.optLong("paid"))}")
                addSectionTitle("🕐 CHỌN KỲ")
                val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                buttons.addView(actionButton("7 ngày") { loadStats(7) }, weightParams())
                buttons.addView(actionButton("30 ngày") { loadStats(30) }, weightParams())
                buttons.addView(actionButton("90 ngày") { loadStats(90) }, weightParams())
                contentBox.addView(buttons)
                status.text = "Đã tải thống kê $days ngày"
            }
        }
    }

    private fun loadLedger() {
        async {
            val data = api.call("partner_ledger").optJSONObject("data") ?: JSONObject()
            ui {
                clearContent()
                addSectionTitle("💰 ĐỐI SOÁT DOANH THU")
                val summary = data.optJSONArray("summary") ?: JSONArray()
                if (summary.length() == 0) addText("Chưa có phát sinh đối soát.")
                var pending = 0L; var paid = 0L
                for (i in 0 until summary.length()) {
                    val s = summary.getJSONObject(i)
                    if (s.optString("status") == "pending") pending += s.optLong("total")
                    if (s.optString("status") == "paid") paid += s.optLong("total")
                }
                addText("🟡 Đang chờ: ${money(pending)}")
                addText("🟢 Đã thanh toán: ${money(paid)}")
                addSectionTitle("📋 GIAO DỊCH GẦN NHẤT")
                val ledger = data.optJSONArray("ledger") ?: JSONArray()
                if (ledger.length() == 0) addText("Chưa có giao dịch.")
                for (i in 0 until ledger.length()) {
                    val x = ledger.getJSONObject(i)
                    addText("${x.optString("created_at")} • ${x.optString("type")} • ${money(x.optLong("amount"))} • ${x.optString("status")}\n${x.optString("description")}")
                }
                status.text = "Đã tải đối soát"
            }
        }
    }

    private fun loadAccount() {
        async {
            val me = api.call("partner_me").optJSONObject("data")?.optJSONObject("partner") ?: JSONObject()
            val contract = api.call("partner_contract").optJSONObject("data")?.optJSONObject("contract")
            ui {
                clearContent()
                addSectionTitle("👤 TÀI KHOẢN SHOP")
                addText("Tên Shop: ${me.optString("store_name")}")
                addText("Tài khoản: ${me.optString("username")}")
                addText("Danh mục: ${me.optString("category")}")
                addText("Điện thoại: ${me.optString("store_phone")}")
                addText("Địa chỉ: ${me.optString("store_address")}")
                addText("Đăng hôm nay: ${me.optInt("posts_today")}/${me.optInt("daily_post_limit")}")
                addSectionTitle("📄 HỢP ĐỒNG")
                val cStatus = contract?.optString("status").orEmpty()
                addText(if (cStatus == "signed") "✅ Đã ký hợp đồng điện tử." else "⚠️ Chưa ký hợp đồng điện tử.")
                if (cStatus != "signed") contentBox.addView(actionButton("✍️ KÝ HỢP ĐỒNG") { showContractScreen() })
                addSectionTitle("ℹ️ QUYỀN TRUY CẬP")
                addText("• Quản lý vé thuộc Shop\n• Upload/cập nhật ảnh vé\n• Cập nhật tồn kho\n• Gửi giá đề xuất cho Admin\n• Nhận và xử lý đơn\n• Xem doanh số và đối soát")
                contentBox.addView(actionButton("🚪 ĐĂNG XUẤT") { doLogout() })
                status.text = "Tài khoản hoạt động"
            }
        }
    }

    private fun showContractScreen() {
        shell()
        rootBox.addView(title("📄 HỢP ĐỒNG HỢP TÁC"))
        status = TextView(this)
        rootBox.addView(status)
        val body = TextView(this).apply { textSize = 15f; setPadding(dp(12), dp(12), dp(12), dp(12)) }
        rootBox.addView(ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(360)); addView(body) })
        val signedName = field("Họ và tên người xác nhận")
        val agree = CheckBox(this).apply { text = "Tôi đã đọc, hiểu và đồng ý với toàn bộ Hợp đồng hợp tác này." }
        val sign = Button(this).apply { text = "✍️ XÁC NHẬN KÝ HỢP ĐỒNG"; isEnabled = false }
        rootBox.addView(signedName); rootBox.addView(agree); rootBox.addView(sign)
        agree.setOnCheckedChangeListener { _, checked -> sign.isEnabled = checked }

        async {
            val data = api.call("partner_contract").optJSONObject("data") ?: JSONObject()
            val contract = data.optJSONObject("contract")
            ui {
                if (contract == null) {
                    body.text = "Hiện chưa có hợp đồng được Admin tạo cho Shop. Vui lòng liên hệ Admin."
                    sign.isEnabled = false
                } else {
                    body.text = contract.optString("body", contract.optString("content", "Không có nội dung hợp đồng."))
                    if (contract.optString("status") == "signed") {
                        body.text = body.text.toString() + "\n\n✅ Hợp đồng đã được ký."
                        sign.isEnabled = false
                        ui { showApp() }
                    }
                }
            }
        }
        sign.setOnClickListener {
            async {
                val name = signedName.text.toString().trim()
                if (name.isBlank()) throw ApiException("Vui lòng nhập họ tên xác nhận.")
                api.call("partner_sign_contract", JSONObject().put("signed_name", name))
                ui { showApp() }
            }
        }
    }

    private fun doLogout() {
        async {
            runCatching { api.call("partner_logout", JSONObject()) }
            ui {
                stopService(Intent(this, OrderAlertService::class.java))
                session.clear()
                showLogin()
            }
        }
    }

    private fun chooseImage(onPicked: (Uri) -> Unit) {
        pendingImageCallback = onPicked
        imagePicker.launch(arrayOf("image/*"))
    }

    private fun readUploadFile(uri: Uri): UploadFile {
        val raw = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw ApiException("Không đọc được ảnh vé.")
        val mime = contentResolver.getType(uri) ?: "image/jpeg"
        val name = displayName(uri)
        return if (raw.size <= 2_500_000) {
            UploadFile(name, mime, raw)
        } else {
            val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size)
                ?: return UploadFile(name, mime, raw)
            val max = 1600
            val scale = minOf(1f, max.toFloat() / maxOf(bitmap.width, bitmap.height).toFloat())
            val resized = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
            val out = ByteArrayOutputStream()
            resized.compress(Bitmap.CompressFormat.JPEG, 82, out)
            if (resized !== bitmap) resized.recycle()
            bitmap.recycle()
            UploadFile(name.substringBeforeLast('.') + ".jpg", "image/jpeg", out.toByteArray())
        }
    }

    private fun displayName(uri: Uri): String {
        return runCatching {
            contentResolver.query(uri, arrayOf("_display_name"), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: "anh_ve_${System.currentTimeMillis()}.jpg"
    }

    private fun loadRemoteImage(url: String, image: ImageView) {
        thread {
            try {
                val conn = java.net.URL(url).openConnection()
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                conn.getInputStream().use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream)
                    ui { if (bitmap != null) image.setImageBitmap(bitmap) }
                }
            } catch (_: Exception) { }
        }
    }

    private fun clearContent() {
        if (::contentBox.isInitialized) contentBox.removeAllViews()
    }

    private fun addSectionTitle(text: String) {
        contentBox.addView(TextView(this).apply {
            this.text = text; textSize = 20f; setTypeface(null, Typeface.BOLD); setPadding(0, dp(10), 0, dp(8))
        })
    }

    private fun addText(s: String) { contentBox.addView(addTextView(s)) }

    private fun addTextView(s: String) = TextView(this).apply {
        text = s; textSize = 16f; setPadding(0, dp(6), 0, dp(6))
    }

    private fun boldText(s: String) = TextView(this).apply {
        text = s; textSize = 17f; setTypeface(null, Typeface.BOLD); setPadding(0, dp(3), 0, dp(5))
    }

    private fun title(s: String) = TextView(this).apply {
        text = s; textSize = 27f; setTypeface(null, Typeface.BOLD); setPadding(0, 0, 0, dp(10))
    }

    private fun field(h: String, password: Boolean = false) = EditText(this).apply {
        hint = h; setPadding(dp(8), dp(6), dp(8), dp(6))
        if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    private fun menuButton(text: String, click: () -> Unit) = Button(this).apply {
        this.text = text; textSize = 11f; setOnClickListener { click() }; minHeight = dp(52)
    }

    private fun actionButton(text: String, click: () -> Unit) = Button(this).apply {
        this.text = text; textSize = 12f; setOnClickListener { click() }; minHeight = dp(46)
    }

    private fun weightParams() = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }

    private fun cardBox() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(10), dp(10), dp(10));
        background = resources.getDrawable(android.R.drawable.dialog_holo_light_frame, theme)
        val lp = LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, dp(5), 0, dp(5)); layoutParams = lp
    }

    private fun metric(icon: String, label: String, value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(10), dp(10), dp(10))
        addView(TextView(this@MainActivity).apply { text = icon; textSize = 22f })
        addView(TextView(this@MainActivity).apply { text = label; textSize = 13f })
        addView(TextView(this@MainActivity).apply { text = value; textSize = 18f; setTypeface(null, Typeface.BOLD) })
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
    }

    private fun verticalGrid() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun divider() = Space(this).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(1)) }

    private fun money(n: Long) = String.format(Locale.US, "%,d đ", n).replace(',', '.')
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun ui(f: () -> Unit) = runOnUiThread(f)

    private fun async(f: () -> Unit) {
        thread {
            try { f() } catch (e: UnauthorizedException) { ui { session.clear(); showLogin() } }
            catch (e: Exception) { ui { if (::status.isInitialized) status.text = e.message ?: "Có lỗi xảy ra" } }
        }
    }

    companion object { private const val DEFAULT_KCN_ID = 1 }
}
