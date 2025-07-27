package com.sstinc.labx

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.util.regex.Pattern

@Composable
fun LoginScreen(onLoginSuccess: (accessToken: String?) -> Unit) {
    val auth = FirebaseAuth.getInstance()
    val db = FirebaseFirestore.getInstance()

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var isRegistering by remember { mutableStateOf(false) }
    var isStaffSignup by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var showAlert by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }

    val classes = remember { (1..4).flatMap { l -> (1..10).map { lvl -> "S$l-${lvl.toString().padStart(2, '0')}" } } + listOf("Staff") }
    val registers = remember { (1..30).map { it.toString().padStart(2, '0') } + listOf("Staff") }
    var selectedClass by remember { mutableStateOf(classes.first()) }
    var selectedRegister by remember { mutableStateOf(registers.first()) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.Black,
        unfocusedTextColor = Color.Black,
        focusedLabelColor = Color.DarkGray,
        unfocusedLabelColor = Color.Gray,
        cursorColor = Color.Black,
        focusedBorderColor = Color.DarkGray,
        unfocusedBorderColor = Color.LightGray
    )


    fun validateEmail(email: String): Boolean {
        return if (isStaffSignup) {
            email.endsWith("@sst.edu.sg")
        } else {
            Pattern.compile("^.+@s20\\d{2}\\.ssts\\.edu\\.sg$").matcher(email).matches()
        }
    }

    fun handleEmailPasswordAuth() {
        if (email.isBlank() || password.isBlank() || (isRegistering && (firstName.isBlank() || lastName.isBlank()))) {
            errorMessage = "Please fill all required fields"
            showAlert = true
            return
        }

        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches() || !validateEmail(email)) {
            errorMessage = if (isStaffSignup) "Staff must use @sst.edu.sg email" else "Students must use school email ending with @s20xx.ssts.edu.sg"
            showAlert = true
            return
        }

        if (isRegistering && password != confirmPassword) {
            errorMessage = "Passwords do not match"
            showAlert = true
            return
        }

        if (isRegistering) {
            auth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val user = auth.currentUser
                        if (user == null) {
                            errorMessage = "User not found after registration"
                            showAlert = true
                            return@addOnCompleteListener
                        }

                        user.sendEmailVerification()
                            .addOnCompleteListener { verificationTask ->
                                if (verificationTask.isSuccessful) {
                                    val uid = user.uid
                                    val userData = hashMapOf(
                                        "firstName" to firstName,
                                        "lastName" to lastName,
                                        "email" to email,
                                        "className" to if (isStaffSignup) "Staff" else selectedClass,
                                        "registerNumber" to if (isStaffSignup) "Staff" else selectedRegister
                                    )
                                    db.collection("users").document(uid).set(userData)
                                        .addOnCompleteListener { setUserDataTask ->
                                            if (setUserDataTask.isSuccessful) {
                                                auth.signOut()
                                                errorMessage = "Verification email sent. Please check your inbox."
                                                showAlert = true
                                                isRegistering = false
                                            } else {
                                                errorMessage = "Failed to save user data: ${setUserDataTask.exception?.localizedMessage}"
                                                showAlert = true
                                            }
                                        }
                                } else {
                                    val ex = verificationTask.exception
                                    errorMessage = "Failed to send verification email: ${ex?.localizedMessage ?: "Unknown error"}"
                                    showAlert = true
                                }
                            }
                    } else {
                        errorMessage = task.exception?.localizedMessage ?: "Registration failed"
                        showAlert = true
                    }
                }
        } else {
            auth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val user = auth.currentUser
                        if (user != null) {
                            onLoginSuccess(null)
                        } else {
                            auth.signOut()
                            errorMessage = "Please verify your email before logging in."
                            showAlert = true
                        }
                    } else {
                        errorMessage = task.exception?.localizedMessage ?: "Login failed"
                        showAlert = true
                    }
                }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = buildAnnotatedString {
                append("Lab")
                withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                    append("X")
                }
            },
            style = MaterialTheme.typography.headlineLarge
        )
        Spacer(Modifier.height(18.dp))

        Text(text = if (isRegistering) "Sign Up" else "Log In", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        if (isRegistering) {
            OutlinedTextField(
                firstName, { firstName = it },
                label = { Text("First Name") },
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(Color.Black),
                colors = fieldColors
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                lastName, { lastName = it },
                label = { Text("Last Name") },
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(Color.Black),
                colors = fieldColors
            )
        }

        OutlinedTextField(
            email, { email = it },
            label = { Text("School Email") },
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(Color.Black),
            colors = fieldColors
        )

        OutlinedTextField(
            password, { password = it },
            label = { Text("Password") },
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(Icons.Filled.Visibility, contentDescription = null)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(Color.Black),
            colors = fieldColors
        )

        if (isRegistering) {
            OutlinedTextField(
                confirmPassword,
                { confirmPassword = it },
                label = { Text("Confirm Password") },
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(Color.Black),
                colors = fieldColors
            )

            if (!isStaffSignup) {
                ClassDropdown(options = classes, selected = selectedClass, onSelectedChange = { selectedClass = it })
                OutlinedTextField(
                    value = selectedRegister,
                    onValueChange = { selectedRegister = it },
                    label = { Text("Index No.") },
                    keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(Color.Black),
                    colors = fieldColors
                )
            }

            TextButton(onClick = { isStaffSignup = !isStaffSignup }) {
                Text(if (isStaffSignup) "Switch to Student Signup" else "Switch to Staff Signup")
            }
        }

        Spacer(Modifier.height(24.dp))

        Button(onClick = { handleEmailPasswordAuth() }, modifier = Modifier.fillMaxWidth()) {
            Text(if (isRegistering) "Sign Up" else "Log In")
        }

        TextButton(onClick = { isRegistering = !isRegistering }) {
            Text(if (isRegistering) "Have an account? Log In" else "No account? Sign Up")
        }

        Spacer(Modifier.height(16.dp))
        Divider()
        Spacer(Modifier.height(16.dp))
    }

    if (showAlert) {
        AlertDialog(
            onDismissRequest = { showAlert = false },
            title = { Text("Authentication Error") },
            text = { Text(errorMessage) },
            confirmButton = {
                TextButton(onClick = { showAlert = false }) {
                    Text("OK")
                }
            }
        )
    }
}

@Composable
fun ClassDropdown(options: List<String>, selected: String, onSelectedChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            label = { Text("Select") },
            readOnly = true,
            trailingIcon = {
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    Modifier.clickable { expanded = true }
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item) },
                    onClick = {
                        onSelectedChange(item)
                        expanded = false
                    }
                )
            }
        }
    }
}