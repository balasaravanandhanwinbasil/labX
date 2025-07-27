package com.sstinc.labx


import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.filled.LocationOn
import kotlinx.coroutines.tasks.await

// --- UTILS ---
fun isoFormat(date: Date): String {
    // Use consistent ISO 8601 with timezone explicitly set
    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault())
    sdf.timeZone = TimeZone.getTimeZone("Asia/Singapore") // or your timezone
    return sdf.format(date)
}

// --- GOOGLE CALENDAR API CALLS ---

suspend fun checkIfTimeSlotFree(
    accessToken: String,
    startTime: Date,
    endTime: Date
): Boolean {
    val client = OkHttpClient()

    val bodyJson = JSONObject().apply {
        put("timeMin", isoFormat(startTime))
        put("timeMax", isoFormat(endTime))
        put("timeZone", "Asia/Singapore")
        put("items", JSONArray().put(JSONObject().put("id", "primary")))
    }

    val requestBody = RequestBody.create(
        "application/json".toMediaTypeOrNull(),
        bodyJson.toString()
    )

    val request = Request.Builder()
        .url("https://www.googleapis.com/calendar/v3/freeBusy")
        .addHeader("Authorization", "Bearer $accessToken")
        .post(requestBody)
        .build()

    return withContext(Dispatchers.IO) {
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return@withContext false

        val json = JSONObject(response.body?.string() ?: "")
        val busyArray = json.getJSONObject("calendars").getJSONObject("primary").getJSONArray("busy")
        busyArray.length() == 0
    }
}

suspend fun addEventToCalendar(
    accessToken: String,
    consultation: Consultation
): Boolean {
    val client = OkHttpClient()

    val start = consultation.date ?: return false
    val end = Date(start.time + 30 * 60 * 1000)

    val eventJson = JSONObject().apply {
        put("summary", "Consultation with ${consultation.student}")
        put("description", consultation.comment ?: "")
        put("start", JSONObject().apply {
            put("dateTime", isoFormat(start))
            put("timeZone", "Asia/Singapore")
        })
        put("end", JSONObject().apply {
            put("dateTime", isoFormat(end))
            put("timeZone", "Asia/Singapore")
        })
    }

    val requestBody = RequestBody.create(
        "application/json".toMediaTypeOrNull(),
        eventJson.toString()
    )

    val request = Request.Builder()
        .url("https://www.googleapis.com/calendar/v3/calendars/primary/events")
        .addHeader("Authorization", "Bearer $accessToken")
        .post(requestBody)
        .build()

    return withContext(Dispatchers.IO) {
        val response = client.newCall(request).execute()
        response.isSuccessful
    }
}

// --- COMPOSABLE SCREEN ---

@Composable
fun ConsultationsScreen(
    currentUser: User,
    isStaff: Boolean,
    consultations: List<Consultation>,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onCreateConsultation: () -> Unit,
    onEditConsultation: (Consultation, String) -> Unit,
    allUsers: List<User> = emptyList()
) {


    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val db = FirebaseFirestore.getInstance()

    var isLoading by remember { mutableStateOf(true) }

    var accessToken by rememberSaveable { mutableStateOf<String?>(null) }
    var showFuture by remember { mutableStateOf(true) }

    var selectedStatus by remember { mutableStateOf("All") }
    val statusOptions = listOf("All", "Pending", "Approved", "Denied")

    // State for showing deny + approve reason dialog
    var denyDialogConsultation by remember { mutableStateOf<Consultation?>(null) }
    var denyReasonText by remember { mutableStateOf("") }

    var approveDialogConsultation by remember { mutableStateOf<Consultation?>(null) }
    var approveCommentText by remember { mutableStateOf("") }

    var buttonsEnabled by remember { mutableStateOf(true) }

    // Google Sign-In launcher for getting token
    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()

    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                val scope = "oauth2:https://www.googleapis.com/auth/calendar.events"
                coroutineScope.launch {
                    val token = withContext(Dispatchers.IO) {
                        GoogleAuthUtil.getToken(context, account!!.account!!, scope)
                    }
                    accessToken = token
                    Toast.makeText(context, "Google Sign-in successful", Toast.LENGTH_SHORT).show()
                }
            } catch (e: ApiException) {
                Toast.makeText(context, "Google Sign-in failed: ${e.statusCode}", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Google Sign-in cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    fun launchGoogleSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope("https://www.googleapis.com/auth/calendar.events"))
            .build()
        val googleSignInClient = GoogleSignIn.getClient(context, gso)
        val signInIntent = googleSignInClient.signInIntent
        googleSignInLauncher.launch(signInIntent)
    }

    // Filter consultations
    val now = Date()
    val filteredConsultations = consultations.filter { consultation ->
        val isFuture = consultation.date?.after(now) ?: false
        val dateFilter = if (showFuture) isFuture else !isFuture

        val statusFilter = when (selectedStatus) {
            "Pending" -> consultation.status.equals("pending", ignoreCase = true)
            "Approved" -> consultation.status.equals("approved", ignoreCase = true)
            "Denied" -> consultation.status.equals("denied", ignoreCase = true)
            else -> true
        }

        dateFilter && statusFilter
    }


    // Helper to map email to display name
    fun getDisplayName(email: String): String {
        return allUsers.find { it.email == email }?.let { "${it.firstName} ${it.lastName}".trim() }
            ?: email
    }

    val isDark = isSystemInDarkTheme()
    val cardBg = if (!isDark) Color(0xFFE9E9F0) else Color(0xFF23232B)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (!isDark) Color.White else Color.Black)
            .padding(16.dp)
    ) {
        // Header: Toggle + Request button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { showFuture = !showFuture }) {
                Text(
                    text = if (showFuture) "Show Future" else "Show Previous",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (!isStaff) {
                Button(onClick = onCreateConsultation) {
                    Text("Request Consultation", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            DropdownMenuSelector(
                label = "Status",
                items = statusOptions,
                selected = selectedStatus,
                onItemSelected = { selectedStatus = it }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (filteredConsultations.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No consultations to display",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                items(filteredConsultations) { consultation ->
                    var expanded by remember { mutableStateOf(false) }
                    val isDenied = consultation.status.equals("denied", ignoreCase = true)
                    val isApproved = consultation.status.equals("approved", ignoreCase = true)
                    val isPending = consultation.status.equals("pending", ignoreCase = true)
                    val statusColor = when {
                        isDenied -> Color(0xFFE74C3C)
                        isApproved -> Color(0xFF34C759)
                        else -> Color(0xFFB0B0B0)
                    }
                    val statusText = when {
                        isDenied -> "Denied"
                        isApproved -> "Approved"
                        else -> "Pending"
                    }
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = 1.dp,
                                color = Color(0x11000000),
                                shape = RoundedCornerShape(20.dp)
                            )
                            .clip(RoundedCornerShape(20.dp))
                            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { expanded = !expanded }
                            .background(cardBg),
                        color = cardBg,
                        tonalElevation = 0.dp,
                        shadowElevation = 2.dp,
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 22.dp, vertical = 20.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        if (isStaff) consultation.student else getDisplayName(consultation.teacher.name),
                                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp),
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.Top)
                                        .padding(start = 8.dp)
                                ) {
                                    Text(
                                        statusText,
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                        maxLines = 1,
                                        modifier = Modifier
                                            .background(
                                                color = statusColor,
                                                shape = RoundedCornerShape(50)
                                            )
                                            .padding(horizontal = 18.dp, vertical = 6.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                consultation.date?.let {
                                    SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(it)
                                } ?: "N/A",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LocationOn,
                                    contentDescription = "Location",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = consultation.location,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Divider(
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                                thickness = 1.dp,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                            if (!expanded) {
                                if (consultation.comment.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        consultation.comment.take(32) + if (consultation.comment.length > 32) "..." else "",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (expanded) {
                                if (consultation.comment.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        consultation.comment,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                if (consultation.reason.isNotBlank()) {
                                    val (label, color) = when {
                                        isApproved -> "Reason for Accept:" to Color(0xFF007AFF)
                                        isDenied -> "Reason for Denial:" to Color(0xFFE74C3C)
                                        else -> "Comment:" to MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                        color = color
                                    )
                                    Text(
                                        consultation.reason,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color(0xFFE74C3C),
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                }
                            }
                            if (isStaff && isPending) {
                                Spacer(modifier = Modifier.height(18.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            approveDialogConsultation = consultation
                                            approveCommentText = ""
                                        },
                                        enabled = true,
                                        shape = RoundedCornerShape(50),
                                        modifier = Modifier.weight(1f)
                                    ) { Text("Approve", modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp)) }
                                    Button(
                                        onClick = {
                                            denyDialogConsultation = consultation
                                        },
                                        enabled = true,
                                        shape = RoundedCornerShape(50),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE74C3C)),
                                        modifier = Modifier.weight(1f)
                                    ) { Text("Deny", modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp)) }
                                }
                            }
                            if (!isStaff && isPending && consultation.student == currentUser.email) {
                                Spacer(modifier = Modifier.height(18.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    IconButton(
                                        onClick = {
                                            db.collection("consultations").document(consultation.id)
                                                .delete()
                                                .addOnSuccessListener {
                                                    Toast.makeText(context, "Consultation deleted", Toast.LENGTH_SHORT).show()
                                                    onRefresh()
                                                }
                                                .addOnFailureListener {
                                                    Toast.makeText(context, "Failed to delete consultation", Toast.LENGTH_SHORT).show()
                                                }

                                        },
                                        modifier = Modifier
                                            .size(44.dp)
                                            .background(Color(0xFFF2F2F7), shape = RoundedCornerShape(50))
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete Consultation", tint = Color(0xFFE74C3C))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Deny reason dialog
    if (denyDialogConsultation != null) {
        AlertDialog(
            onDismissRequest = {
                denyDialogConsultation = null
                denyReasonText = ""
            },
            title = { Text("Reason for Denial") },
            text = {
                OutlinedTextField(
                    value = denyReasonText,
                    onValueChange = { denyReasonText = it },
                    label = { Text("Reason") },
                    placeholder = { Text("Enter reason for denying consultation") },
                    singleLine = false,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val consultation = denyDialogConsultation
                        if (consultation != null) {
                            buttonsEnabled = false
                            coroutineScope.launch {
                                db.collection("consultations").document(consultation.id)
                                    .update(
                                        mapOf(
                                            "status" to "denied",
                                            "reason" to denyReasonText.trim()
                                        )
                                    )
                                    .addOnSuccessListener {
                                        Toast.makeText(
                                            context,
                                            "Consultation denied with reason.",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        onRefresh()
                                        denyDialogConsultation = null
                                        denyReasonText = ""
                                    }
                                    .addOnFailureListener {
                                        Toast.makeText(
                                            context,
                                            "Failed to update consultation.",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                    .also {
                                        buttonsEnabled = true
                                    }
                            }
                        }
                    },
                    enabled = denyReasonText.isNotBlank()
                ) {
                    Text("Submit")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        denyDialogConsultation = null
                        denyReasonText = ""
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // reason of acceot
    if (approveDialogConsultation != null) {
        AlertDialog(
            onDismissRequest = {
                approveDialogConsultation = null
                approveCommentText = ""
            },
            title = { Text("Add an optional comment for approval") },
            text = {
                OutlinedTextField(
                    value = approveCommentText,
                    onValueChange = { approveCommentText = it },
                    label = { Text("Comment") },
                    placeholder = { Text("Enter a comment (optional)") },
                    singleLine = false,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val consultation = approveDialogConsultation
                        if (consultation != null) {
                            buttonsEnabled = false
                            coroutineScope.launch {
                                val db = FirebaseFirestore.getInstance()
                                val newStart = consultation.date ?: return@launch
                                val newEnd = Date(newStart.time + 30 * 60 * 1000)

                                try {
                                    val conflictingQuery = db.collection("consultations")
                                        .whereEqualTo("teacherEmail", consultation.teacher.email)
                                        .whereEqualTo("status", "approved")
                                        .get()
                                        .await()

                                    val conflictExists = conflictingQuery.documents.any { doc ->
                                        val start = doc.getDate("date") ?: return@any false
                                        val end = Date(start.time + 30 * 60 * 1000)
                                        newStart.before(end) && newEnd.after(start)
                                    }

                                    if (conflictExists) {
                                        Toast.makeText(context, "Time slot conflicts with another approved consultation.", Toast.LENGTH_LONG).show()
                                        buttonsEnabled = true
                                        return@launch
                                    }

                                    db.collection("consultations").document(consultation.id)
                                        .update(
                                            mapOf(
                                                "status" to "approved",
                                                "comment" to approveCommentText.trim()
                                            )
                                        )
                                        .addOnSuccessListener {
                                            Toast.makeText(context, "Consultation approved", Toast.LENGTH_SHORT).show()
                                            onRefresh()
                                            approveDialogConsultation = null
                                            approveCommentText = ""
                                        }
                                        .addOnFailureListener {
                                            Toast.makeText(context, "Failed to approve consultation", Toast.LENGTH_SHORT).show()
                                        }
                                        .also {
                                            buttonsEnabled = true
                                        }

                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    Toast.makeText(context, "Error checking conflicts", Toast.LENGTH_SHORT).show()
                                    buttonsEnabled = true
                                }
                            }
                        }
                    },
                    enabled = buttonsEnabled
                ) {
                    Text("Approve")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        approveDialogConsultation = null
                        approveCommentText = ""
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateConsultationSheet(
    consultationId: String? = null,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit,
    currentUser: User,
    teacherList: List<Staff>
) {
    val context = LocalContext.current
    val db = FirebaseFirestore.getInstance()

    var selectedTeacher by remember { mutableStateOf<Staff?>(null) }
    var selectedDate by remember { mutableStateOf<Date?>(null) }
    var selectedTime by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var location by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }

    val locationOptions = listOf(
        "Outside Staffroom",
        "Classroom (specify in comments)",
        "Outside Labs (Level 1)",
        "Outside Labs (Level 2)",
        "Online",
        "Others (in comments)"
    )

    fun openDatePicker() {
        val cal = Calendar.getInstance()
        DatePickerDialog(context, { _, y, m, d ->
            cal.set(y, m, d)
            selectedDate = cal.time
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    fun openTimePicker() {
        val cal = Calendar.getInstance()
        TimePickerDialog(context, { _, h, m ->
            selectedTime = h to m
        }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (consultationId == null) "Create Consultation" else "Update Consultation",
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(Modifier.height(12.dp))

            var searchQuery by remember { mutableStateOf("") }
            val filteredTeachers = if (searchQuery.isBlank()) {
                teacherList
            } else {
                teacherList.filter {
                    it.name.contains(searchQuery, ignoreCase = true)
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search Teacher") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            DropdownMenuSelector(
                label = "Teacher",
                items = filteredTeachers.map { it.name },
                selected = selectedTeacher?.name ?: "Select a teacher",
                onItemSelected = { name -> selectedTeacher = teacherList.find { it.name == name } }
            )


            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { openDatePicker() }, modifier = Modifier.weight(1f)) {
                    Text(selectedDate?.let { SimpleDateFormat("dd/MM/yyyy").format(it) } ?: "Select Date")
                }
                OutlinedButton(onClick = { openTimePicker() }, modifier = Modifier.weight(1f)) {
                    Text(selectedTime?.let { String.format("%02d:%02d", it.first, it.second) } ?: "Select Time")
                }
            }

            DropdownMenuSelector(
                label = "Location",
                items = locationOptions,
                selected = if (location.isBlank()) "Select a location" else location,
                onItemSelected = { selected -> location = selected }
            )

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = comment,
                onValueChange = { comment = it },
                label = { Text("Comment") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    if (selectedTeacher != null && selectedDate != null && selectedTime != null) {
                        val cal = Calendar.getInstance().apply {
                            time = selectedDate!!
                            set(Calendar.HOUR_OF_DAY, selectedTime!!.first)
                            set(Calendar.MINUTE, selectedTime!!.second)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }

                        val data = hashMapOf(
                            "teacherName" to selectedTeacher!!.name,
                            "teacherEmail" to selectedTeacher!!.email,
                            "date" to com.google.firebase.Timestamp(cal.time),
                            "comment" to comment,
                            "student" to currentUser.email,
                            "studentUid" to currentUser.id,
                            "status" to "pending",
                            "location" to location
                        )

                        if (consultationId == null) {
                            db.collection("consultations")
                                .add(data)
                                .addOnSuccessListener {
                                    Toast.makeText(context, "Consultation created", Toast.LENGTH_SHORT).show()
                                    onSuccess()
                                }
                                .addOnFailureListener { e ->
                                    Toast.makeText(context, "Failed to create: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                                }
                        } else {
                            db.collection("consultations")
                                .document(consultationId)
                                .set(data, com.google.firebase.firestore.SetOptions.merge())
                                .addOnSuccessListener {
                                    Toast.makeText(context, "Consultation updated", Toast.LENGTH_SHORT).show()
                                    onSuccess()
                                }
                                .addOnFailureListener { e ->
                                    Toast.makeText(context, "Failed to update: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                                }
                        }
                        onDismiss()
                    } else {
                        Toast.makeText(context, "Please fill all fields", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedTeacher != null && selectedDate != null && selectedTime != null
            ) {
                Text(if (consultationId == null) "Create Consultation" else "Update Consultation")
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text("Cancel")
            }
        }
    }
}

@Composable
fun ConsultationsCalendarScreen(
    currentUser: User,
    isStaff: Boolean,
    db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    var consultations by remember { mutableStateOf<List<Consultation>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    val sdf = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }

    LaunchedEffect(currentUser.email, isStaff) {
        isLoading = true
        try {
            val query = if (isStaff) {
                db.collection("consultations")
                    .whereEqualTo("teacherEmail", currentUser.email)
                    .whereEqualTo("status", "approved")
            } else {
                db.collection("consultations")
                    .whereEqualTo("student", currentUser.email)
                    .whereEqualTo("status", "approved")
            }


            val snapshot = query.get().await()
            val loaded = snapshot.documents.mapNotNull { doc ->
                val teacherName = doc.getString("teacherName") ?: return@mapNotNull null
                val teacherEmail = doc.getString("teacherEmail") ?: return@mapNotNull null
                val dateTimestamp = doc.getTimestamp("date") ?: return@mapNotNull null
                val comment = doc.getString("comment") ?: ""
                val student = doc.getString("student") ?: ""
                val studentUid = doc.getString("studentUid") ?: ""
                val status = doc.getString("status") ?: "pending"
                val reason = doc.getString("reason") ?: ""
                val location = doc.getString("location") ?: ""

                Consultation(
                    id = doc.id,
                    teacher = Staff(teacherName, teacherEmail),
                    date = dateTimestamp.toDate(),
                    comment = comment,
                    student = student,
                    studentUid = studentUid,
                    status = status,
                    reason = reason,
                    location = location
                )
            }
            consultations = loaded.sortedBy { it.date }
        } catch (e: Exception) {
            // Handle error (e.g. show a Toast or Snackbar)
            consultations = emptyList()
        }
        isLoading = false
    }

    Scaffold() { paddingValues ->
        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (consultations.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No consultations found.")
            }
        } else {
            // Group consultations by date
            val grouped = consultations.groupBy { sdf.format(it.date) }

            LazyColumn(contentPadding = paddingValues) {
                grouped.forEach { (dateString, consultationsForDate) ->
                    item {
                        Text(
                            text = dateString,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                    items(consultationsForDate) { consultation ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    if (isStaff) consultation.student else consultation.teacher.name,
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(consultation.date),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                if (consultation.comment.isNotBlank()) {
                                    Text("Note: ${consultation.comment}", style = MaterialTheme.typography.bodySmall)
                                }
                                if (consultation.location.isNotBlank()) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.LocationOn,
                                            contentDescription = "Location",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = consultation.location,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


fun Consultation.toMap(): Map<String, Any> {
    return mapOf(
        "teacherName" to teacher.name,
        "teacherEmail" to teacher.email,
        "date" to date,
        "comment" to comment,
        "student" to student,
        "studentUid" to studentUid,
        "status" to status,
        "reason" to reason,
        "location" to location
    )
}

data class Consultation(
    val id: String = UUID.randomUUID().toString(),
    val teacher: Staff,
    val date: Date,
    val comment: String,
    val student: String,
    val studentUid: String = "",
    val status: String = "pending",
    val reason: String = "",
    val location: String = ""
)