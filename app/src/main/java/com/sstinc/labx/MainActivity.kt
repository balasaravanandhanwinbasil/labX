package com.sstinc.labx

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.sstinc.labx.ui.theme.LabXTheme
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirebaseApp.initializeApp(this)
        enableEdgeToEdge()

        setContent {
            LabXTheme  {
                val auth = FirebaseAuth.getInstance()
                var isLoggedIn by remember { mutableStateOf(false) }
                var accessTokenState by remember { mutableStateOf<String?>(null) }

                LaunchedEffect(Unit) {
                    val currentUser = auth.currentUser
                    if (currentUser != null) {
                        isLoggedIn = true
                        currentUser.getIdToken(true).addOnSuccessListener { result ->
                            accessTokenState = result.token
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFFE0FFFF))
                ) {
                    if (isLoggedIn) {
                        MainScreen(
                            onLogout = {
                                auth.signOut()
                                isLoggedIn = false
                                accessTokenState = null
                            }
                        )
                    } else {
                        LoginScreen(onLoginSuccess = { token ->
                            accessTokenState = token
                            isLoggedIn = true
                        })
                    }
                }
            }
        }
    }
}


@Composable
fun MainScreen(
    onLogout: () -> Unit
) {
    val auth = FirebaseAuth.getInstance()
    val db = FirebaseFirestore.getInstance()
    val uid = auth.currentUser?.uid

    var selectedTab by remember { mutableStateOf(0) }
    var showProfile by remember { mutableStateOf(false) }
    var showCreateConsultation by remember { mutableStateOf(false) }

    var user by remember { mutableStateOf<User?>(null) }
    var consultations by remember { mutableStateOf(emptyList<Consultation>()) }
    var teachers by remember { mutableStateOf(emptyList<Staff>()) }
    var isLoading by remember { mutableStateOf(true) }
    var isStaff by remember { mutableStateOf(false) }

    var consultationListener by remember { mutableStateOf<ListenerRegistration?>(null) }
    val coroutineScope = rememberCoroutineScope()

    suspend fun loadUserAndData() {
        if (uid == null) return
        isLoading = true
        try {
            val userDoc = db.collection("users").document(uid).get().await()
            userDoc?.let {
                user = User(
                    id = uid,
                    firstName = it.getString("firstName") ?: "",
                    lastName = it.getString("lastName") ?: "",
                    email = it.getString("email") ?: "",
                    className = it.getString("className") ?: "",
                    registerNumber = it.getString("registerNumber") ?: ""
                )
                isStaff = user?.className.equals("Staff", ignoreCase = true)
            }
            val email = user?.email ?: return
            consultations = loadConsultationsFromFirestore(db, isStaff, email)
            teachers = db.collection("users")
                .whereEqualTo("className", "Staff")
                .get()
                .await()
                .documents.mapNotNull { doc ->
                    val name = "${doc.getString("firstName")} ${doc.getString("lastName")}".trim()
                    val email = doc.getString("email") ?: return@mapNotNull null
                    Staff(name, email)
                }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(uid) {
        loadUserAndData()
    }

    LaunchedEffect(user?.email, isStaff) {
        consultationListener?.remove()
        val email = user?.email ?: return@LaunchedEffect

        consultationListener = try {
            val query = if (isStaff) {
                db.collection("consultations")
                    .whereEqualTo("teacherEmail", email)
                    .orderBy("date")
            } else {
                db.collection("consultations")
                    .whereEqualTo("student", email)
                    .orderBy("date")
            }

            query.addSnapshotListener { snapshot, error ->
                if (error != null) {
                    error.printStackTrace()
                    return@addSnapshotListener
                }
                snapshot?.let {
                    consultations = it.documents.mapNotNull { doc -> parseConsultation(doc) }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            consultationListener?.remove()
        }
    }

    fun refreshConsultations() {
        coroutineScope.launch {
            loadUserAndData()
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Today, contentDescription = null) },
                    label = { Text("Consultations") },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.CalendarToday, contentDescription = null) },
                    label = { Text("Calendar") },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Science, contentDescription = null) },
                    label = { Text("Lab Booking") },
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (selectedTab) {
                0 -> user?.let { currentUser ->
                    ScreenWithTopBar(
                        title = "Consultations",
                        onProfileClick = { showProfile = true },
                        onLogout = {
                            auth.signOut()
                            onLogout()
                        }
                    ) {
                        ConsultationsScreen(
                            currentUser = currentUser,
                            isStaff = isStaff,
                            consultations = consultations,
                            isRefreshing = false,
                            onRefresh = ::refreshConsultations,
                            onCreateConsultation = { showCreateConsultation = true },
                            onEditConsultation = { updatedConsultation, changeReason ->
                                val updates = mapOf(
                                    "comment" to updatedConsultation.comment,
                                    "date" to updatedConsultation.date,
                                    "status" to "pending",
                                    "reason" to changeReason
                                )
                                FirebaseFirestore.getInstance().collection("consultations")
                                    .document(updatedConsultation.id)
                                    .update(updates)
                            }
                        )
                    }
                }
                1 -> user?.let { currentUser ->
                    ConsultationsCalendarScreen(
                        currentUser = currentUser,
                        isStaff = isStaff
                    )
                }
            }

            if (showCreateConsultation && user != null) {
                CreateConsultationSheet(
                    consultationId = null,
                    onDismiss = { showCreateConsultation = false },
                    onSuccess = {
                        showCreateConsultation = false
                        refreshConsultations()
                    },
                    currentUser = user!!,
                    teacherList = teachers
                )
            }

            if (showProfile && user != null) {
                ProfileScreen(
                    uid = user!!.id,
                    onDismiss = { showProfile = false },
                    onSave = { updatedUser ->
                        user = updatedUser
                        showProfile = false
                    }
                )
            }
        }
    }
}


// --- parseConsultation.kt ---

fun parseConsultation(doc: com.google.firebase.firestore.DocumentSnapshot): Consultation? {
    return try {
        val teacherName = doc.getString("teacherName") ?: return null
        val teacherEmail = doc.getString("teacherEmail") ?: ""
        val timestamp = doc.getDate("date") ?: return null
        val comment = doc.getString("comment") ?: ""
        val student = doc.getString("student") ?: ""
        val studentUid = doc.getString("studentUid") ?: ""
        val status = doc.getString("status") ?: "pending"
        val reason = doc.getString("reason") ?: ""

        Consultation(
            id = doc.id,
            teacher = Staff(teacherName, teacherEmail),
            date = timestamp,
            comment = comment,
            student = student,
            studentUid = studentUid,
            status = status,
            reason = reason
        )
    } catch (e: Exception) {
        null
    }
}

suspend fun loadConsultationsFromFirestore(
    db: FirebaseFirestore,
    isStaff: Boolean,
    email: String
): List<Consultation> {
    val querySnapshot = if (isStaff) {
        db.collection("consultations")
            .whereEqualTo("teacherEmail", email)
            .orderBy("date")
            .get(com.google.firebase.firestore.Source.SERVER)
            .await()
    } else {
        db.collection("consultations")
            .whereEqualTo("student", email)
            .orderBy("date")
            .get(com.google.firebase.firestore.Source.SERVER)
            .await()
    }

    return querySnapshot.documents.mapNotNull { doc -> parseConsultation(doc) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenWithTopBar(
    title: String,
    onProfileClick: () -> Unit,
    onLogout: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title, style = MaterialTheme.typography.headlineMedium) },
            actions = {
                IconButton(onClick = onProfileClick) {
                    Icon(Icons.Default.Person, contentDescription = "Profile")
                }
                IconButton(onClick = onLogout) {
                    Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Logout")
                }
            }
        )
        HorizontalDivider()
        Box(modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}

@Composable
fun ProfileScreen(
    uid: String,
    onDismiss: () -> Unit,
    onSave: (User) -> Unit
) {
    val db = remember { FirebaseFirestore.getInstance() }

    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var className by remember { mutableStateOf("") }
    var registerNumber by remember { mutableStateOf("") }

    val classes = (1..4).flatMap { level -> (1..10).map { "S$level-${it.toString().padStart(2, '0')}" } } + listOf("Staff")
    val registerNumbers = (1..30).map { it.toString().padStart(2, '0') } + listOf("Staff")

    LaunchedEffect(uid) {
        try {
            val doc = db.collection("users").document(uid).get().await()
            if (doc.exists()) {
                firstName = doc.getString("firstName") ?: ""
                lastName = doc.getString("lastName") ?: ""
                email = doc.getString("email") ?: ""
                className = doc.getString("className") ?: classes.first()
                registerNumber = doc.getString("registerNumber") ?: registerNumbers.first()
            } else {
                className = classes.first()
                registerNumber = registerNumbers.first()
            }
            isLoading = false
        } catch (e: Exception) {
            errorMessage = "Failed to load profile: ${e.localizedMessage}"
            isLoading = false
        }
    }

    if (isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    if (errorMessage != null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Error") },
            text = { Text(errorMessage!!) },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("OK") }
            }
        )
        return
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("Profile", style = MaterialTheme.typography.headlineMedium)

                Spacer(Modifier.height(24.dp))

                OutlinedTextField(
                    value = firstName,
                    onValueChange = { firstName = it },
                    label = { Text("First Name") },
                    placeholder = { Text("Type your first name here!") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = lastName,
                    onValueChange = { lastName = it },
                    label = { Text("Last Name") },
                    placeholder = { Text("Type your last name here!") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    placeholder = { Text("Type your email here!") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))

                DropdownMenuSelector(
                    label = "Class",
                    items = classes,
                    selected = className,
                    onItemSelected = { className = it }
                )
                Spacer(Modifier.height(12.dp))

                DropdownMenuSelector(
                    label = "Register Number",
                    items = registerNumbers,
                    selected = registerNumber,
                    onItemSelected = { registerNumber = it }
                )

                Spacer(Modifier.height(36.dp))

                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(16.dp))
                    Button(onClick = {
                        val updatedUser = User(
                            id = uid,
                            firstName = firstName.trim(),
                            lastName = lastName.trim(),
                            email = email.trim(),
                            className = className,
                            registerNumber = registerNumber
                        )
                        db.collection("users").document(uid).set(updatedUser.toMap())
                            .addOnSuccessListener { onSave(updatedUser) }
                            .addOnFailureListener {
                                // Optional: handle failure
                            }
                    }) {
                        Text("Save")
                    }
                }
            }
        }
    }
}

// Extension function to convert User to Map<String, Any> for Firestore
fun User.toMap(): Map<String, Any> {
    return mapOf(
        "firstName" to firstName,
        "lastName" to lastName,
        "email" to email,
        "className" to className,
        "registerNumber" to registerNumber
    )
}

@Composable
fun DropdownMenuSelector(
    label: String,
    items: List<String>,
    selected: String,
    onItemSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = {
                IconButton(onClick = { expanded = true }) {
                    Icon(Icons.Default.ArrowDropDown, contentDescription = "Select $label")
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item) },
                    onClick = {
                        onItemSelected(item)
                        expanded = false
                    }
                )
            }
        }
    }
}

// Data models

data class User(
    val id: String = UUID.randomUUID().toString(),
    val firstName: String,
    val lastName: String,
    val email: String,
    val className: String,
    val registerNumber: String
)

data class Staff(
    val name: String,
    val email: String
)


@Composable
fun LocationDropdown(
    locations: List<String>,
    selectedLocation: String,
    onLocationSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier
        .fillMaxWidth()
        .wrapContentSize()) {  // Ensure it wraps content for dropdown positioning

        OutlinedTextField(
            value = selectedLocation,
            onValueChange = {},
            readOnly = true,
            label = { Text("Location") },
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true },
            trailingIcon = {
                IconButton(onClick = { expanded = true }) {
                    Icon(Icons.Default.ArrowDropDown, contentDescription = "Select Location")
                }
            }
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth()
        ) {
            locations.forEach { location ->
                DropdownMenuItem(
                    text = { Text(location) },
                    onClick = {
                        onLocationSelected(location)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun LabBookingScreen(modifier: Modifier = Modifier) {
    val timeSlots = listOf(
        "08:00", "08:20", "08:40", "09:00",
        "09:20", "09:40", "10:00", "10:20",
        "10:40", "11:00", "11:20", "11:40",
        "12:00", "12:20", "12:40", "13:00",
        "13:20", "13:40", "14:00", "14:20",
        "14:40", "15:00", "15:20", "15:40",
        "16:00", "16:20", "16:40", "17:00",
        "17:20", "17:40", "18:00", "18:20"
    )

    val locations = listOf("Research Lab", "Physics Lab", "Chemistry Lab", "Biology Lab")

    var selectedLocation by remember { mutableStateOf(locations.first()) }

    // Store start and end indices of the selection range:
    var selectionStartIndex by remember { mutableStateOf<Int?>(null) }
    var selectionEndIndex by remember { mutableStateOf<Int?>(null) }

    // Helper to check if a slot is selected in the range
    fun isSlotSelected(index: Int): Boolean {
        val start = selectionStartIndex
        val end = selectionEndIndex
        return if (start != null && end != null) {
            index in minOf(start, end)..maxOf(start, end)
        } else {
            start == index
        }
    }

    val context = LocalContext.current

    Column(modifier = modifier.padding(16.dp)) {
        Text("Book a Lab", style = MaterialTheme.typography.titleLarge)

        Spacer(modifier = Modifier.height(12.dp))

        LocationDropdown(
            locations = locations,
            selectedLocation = selectedLocation,
            onLocationSelected = { selectedLocation = it }
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text("Select Time Slot", style = MaterialTheme.typography.titleMedium)

        Spacer(modifier = Modifier.height(12.dp))

        val chunkedSlots = timeSlots.chunked(4) // 4 columns

        Column {
            chunkedSlots.forEachIndexed { rowIndex, rowSlots ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowSlots.forEachIndexed { colIndex, slot ->
                        val slotIndex = rowIndex * 4 + colIndex

                        Button(
                            onClick = {
                                when {
                                    selectionStartIndex == null -> {
                                        // Start new selection range
                                        selectionStartIndex = slotIndex
                                        selectionEndIndex = null
                                    }
                                    selectionEndIndex == null -> {
                                        // Set end of selection range or reset if same slot
                                        selectionEndIndex = if (slotIndex == selectionStartIndex) null else slotIndex
                                    }
                                    else -> {
                                        // Reset to new start slot
                                        selectionStartIndex = slotIndex
                                        selectionEndIndex = null
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSlotSelected(slotIndex)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (isSlotSelected(slotIndex)) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(slot)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                val startIndex = selectionStartIndex
                val endIndex = selectionEndIndex ?: selectionStartIndex
                if (startIndex != null && endIndex != null) {
                    val startTime = timeSlots[minOf(startIndex, endIndex)]
                    val endTime = timeSlots[maxOf(startIndex, endIndex)]
                    Toast.makeText(context, "Booked $selectedLocation from $startTime to $endTime", Toast.LENGTH_SHORT).show()
                }
            },
            enabled = selectionStartIndex != null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Confirm Booking")
        }
    }
}

