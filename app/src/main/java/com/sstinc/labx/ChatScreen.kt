package com.sstinc.labx

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

data class ChatMessage(
    val id: String,
    val senderId: String,
    val senderFirstName: String,
    val senderLastName: String,
    val text: String,
    val timestamp: Date,
    val replyToId: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    currentUser: User,
    isStaff: Boolean,
    modifier: Modifier = Modifier
) {
    val db = FirebaseFirestore.getInstance()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    var allMessages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var newMessageText by remember { mutableStateOf("") }
    var replyToMessage by remember { mutableStateOf<ChatMessage?>(null) }

    DisposableEffect(Unit) {
        val listener = db.collection("chatMessages")
            .orderBy("timestamp")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    error.printStackTrace()
                    Toast.makeText(context, "Failed to load messages.", Toast.LENGTH_SHORT).show()
                    return@addSnapshotListener
                }
                allMessages = snapshot?.documents?.mapNotNull { doc ->
                    try {
                        ChatMessage(
                            id = doc.id,
                            senderId = doc.getString("senderId") ?: "",
                            senderFirstName = doc.getString("senderFirstName") ?: "",
                            senderLastName = doc.getString("senderLastName") ?: "",
                            text = doc.getString("text") ?: "",
                            timestamp = doc.getTimestamp("timestamp")?.toDate() ?: Date(0),
                            replyToId = doc.getString("replyToId")
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                        null
                    }
                } ?: emptyList()
            }
        onDispose { listener.remove() }
    }

    fun sendMessage() {
        val text = newMessageText.trim()
        if (text.isEmpty()) return

        val messageData = mapOf(
            "senderId" to currentUser.id,
            "senderFirstName" to currentUser.firstName,
            "senderLastName" to currentUser.lastName,
            "text" to text,
            "timestamp" to Timestamp.now(),
            "replyToId" to replyToMessage?.id
        )

        coroutineScope.launch {
            try {
                db.collection("chatMessages").add(messageData).await()
                newMessageText = ""
                replyToMessage = null
                Toast.makeText(context, "Message sent", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "Failed to send message", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun deleteMessage(messageId: String) {
        coroutineScope.launch {
            try {
                db.collection("chatMessages").document(messageId).delete().await()
                Toast.makeText(context, "Message deleted", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "Failed to delete message", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val rootMessages = allMessages.filter { it.replyToId == null }.sortedBy { it.timestamp }
    val repliesGrouped = allMessages.groupBy { it.replyToId }

    Column(modifier = modifier.fillMaxSize().padding(8.dp)) {
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            reverseLayout = true
        ) {
            items(rootMessages.reversed()) { msg ->
                MessageCard(msg, currentUser, isStaff, onReply = { replyToMessage = it }, onDelete = { deleteMessage(it.id) })

                // Replies (nested)
                repliesGrouped[msg.id]?.sortedBy { it.timestamp }?.forEach { reply ->
                    MessageCard(
                        message = reply,
                        currentUser = currentUser,
                        isStaff = isStaff,
                        modifier = Modifier.padding(start = 32.dp),
                        onReply = { replyToMessage = it },
                        onDelete = { deleteMessage(it.id) }
                    )
                }
            }
        }

        if (replyToMessage != null) {
            Text(
                "Replying to ${replyToMessage!!.senderFirstName}: ${replyToMessage!!.text.take(40)}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            TextButton(onClick = { replyToMessage = null }) {
                Text("Cancel Reply")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = newMessageText,
                onValueChange = { newMessageText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message...") }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { sendMessage() },
                enabled = newMessageText.isNotBlank()
            ) {
                Text("Send")
            }
        }
    }
}

@Composable
fun MessageCard(
    message: ChatMessage,
    currentUser: User,
    isStaff: Boolean,
    modifier: Modifier = Modifier,
    onReply: (ChatMessage) -> Unit,
    onDelete: (ChatMessage) -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable { onReply(message) },
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${message.senderFirstName} ${message.senderLastName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(message.text, style = MaterialTheme.typography.bodyLarge)
                Text(
                    SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())
                        .format(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (message.senderId == currentUser.id || isStaff) {
                IconButton(onClick = { onDelete(message) }) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete message"
                    )
                }
            }
        }
    }
}