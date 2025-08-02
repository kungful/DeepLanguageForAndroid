package com.example.deepenglish

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.textfield.TextInputEditText
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.util.concurrent.TimeUnit
import android.media.MediaPlayer
import java.net.URLEncoder
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.LinearLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private val client: OkHttpClient
    private val gson = Gson()
    private var currentMediaPlayer: MediaPlayer? = null

    private lateinit var sharedPreferences: SharedPreferences

    private lateinit var generateButton: Button
    private lateinit var readWordButton: Button
    private lateinit var wordInput: TextInputEditText
    private lateinit var sentenceOutputTextView: TextView
    private lateinit var fabSettings: FloatingActionButton
    private lateinit var prevButton: Button
    private lateinit var nextButton: Button

    private var deepseekApiKey: String = ""
    private var llmSystemPrompt: String = ""
    private var promptTemplate: String = ""

    private val wordHistory = mutableListOf<WordDetails>()
    private var currentIndex: Int = -1

    companion object {
        private const val DEFAULT_SYSTEM_PROMPT = "You are a helpful assistant that provides sentence examples, phonetics, and detailed translations with parts of speech in a JSON format. Use common abbreviations for parts of speech (e.g., n., v., adj.)."
        private const val DEFAULT_PROMPT_TEMPLATE = """
For the word "{word}", provide the following in a JSON format:
1. A simple English sentence using the word.
2. The Chinese translation of the sentence.
3. A grammatical explanation of the sentence in Chinese.
4. The International Phonetic Alphabet (IPA) transcription.
5. A list of its Chinese translations, including part of speech and definition.

Example JSON format for the word "book":
{
  "sentence": "I need to book a flight to Beijing.",
  "sentence_translation": "我需要预订一张去北京的机票。",
  "sentence_grammar": "在这个句子中, 'book' 用作动词。句子结构是 '主语 + need to + 动词 (book) + 宾语 (a flight)'。",
  "phonetics": "/bʊk/",
  "translations": [
    {
      "partOfSpeech": "n.",
      "definition": "书, 书籍; 卷, 册"
    },
    {
      "partOfSpeech": "v.",
      "definition": "预订, 预约"
    }
  ]
}
"""
        private const val API_URL = "https://api.deepseek.com/chat/completions"
        private const val PREFS_NAME = "app_settings"
        private const val KEY_WORD_HISTORY = "word_history"
        private const val KEY_CURRENT_INDEX = "current_index"
    }

    init {
        val logging = HttpLoggingInterceptor()
        logging.setLevel(HttpLoggingInterceptor.Level.BODY)
        client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS) // Increase connect timeout
            .readTimeout(30, TimeUnit.SECONDS)    // Increase read timeout
            .writeTimeout(30, TimeUnit.SECONDS)   // Increase write timeout
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        generateButton = findViewById(R.id.button2)
        readWordButton = findViewById(R.id.readWordButton)
        wordInput = findViewById(R.id.textInputEditText)
        sentenceOutputTextView = findViewById(R.id.sentenceOutputTextView)
        fabSettings = findViewById(R.id.fab_settings)
        prevButton = findViewById(R.id.button3)
        nextButton = findViewById(R.id.button4)

        loadSettings()
        loadWordHistory() // Load word history when activity is created
        updateNavigationButtons() // Set initial state of navigation buttons

        // Display the current word details if history is not empty
        if (wordHistory.isNotEmpty() && currentIndex != -1) {
            displayCurrentWordDetails()
        }


        generateButton.setOnClickListener {
            val word = wordInput.text.toString().trim()
            if (word.isNotEmpty()) {
                CoroutineScope(Dispatchers.Main).launch {
                    generateSentence(word, sentenceOutputTextView)
                }
            } else {
                Toast.makeText(this, "请输入一个单词", Toast.LENGTH_SHORT).show()
            }
        }

        readWordButton.setOnClickListener {
            val word = wordInput.text.toString().trim()
            if (word.isNotEmpty()) {
                CoroutineScope(Dispatchers.Main).launch {
                    playWordAudio(word)
                }
            } else {
                Toast.makeText(this, "请输入一个单词", Toast.LENGTH_SHORT).show()
            }
        }

        fabSettings.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        prevButton.setOnClickListener {
            if (currentIndex > 0) {
                currentIndex--
                displayCurrentWordDetails()
                updateNavigationButtons()
            }
        }

        nextButton.setOnClickListener {
            if (currentIndex < wordHistory.size - 1) {
                currentIndex++
                displayCurrentWordDetails()
                updateNavigationButtons()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        saveWordHistory() // Save word history when activity is paused
    }

    override fun onResume() {
        super.onResume()
        loadSettings()
        // No need to load word history here again, as it's done in onCreate
        // and onResume is called after onCreate.
        // If the activity was merely paused and not destroyed, wordHistory would still be in memory.
        // If it was destroyed and recreated, onCreate would handle the load.
    }

    private fun loadSettings() {
        deepseekApiKey = sharedPreferences.getString("deepseek_api_key", "") ?: ""
        llmSystemPrompt = sharedPreferences.getString("llm_system_prompt", DEFAULT_SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT
        promptTemplate = sharedPreferences.getString("prompt_template", DEFAULT_PROMPT_TEMPLATE) ?: DEFAULT_PROMPT_TEMPLATE
    }

    private fun saveWordHistory() {
        val historyJson = gson.toJson(wordHistory)
        sharedPreferences.edit().putString(KEY_WORD_HISTORY, historyJson).apply()
        sharedPreferences.edit().putInt(KEY_CURRENT_INDEX, currentIndex).apply()
    }

    private fun loadWordHistory() {
        val historyJson = sharedPreferences.getString(KEY_WORD_HISTORY, null)
        if (historyJson != null) {
            val type = com.google.gson.reflect.TypeToken.getParameterized(MutableList::class.java, WordDetails::class.java).type
            wordHistory.clear()
            wordHistory.addAll(gson.fromJson(historyJson, type))
            currentIndex = sharedPreferences.getInt(KEY_CURRENT_INDEX, -1)
        }
    }

    private suspend fun generateSentence(word: String, textView: TextView) {
        if (deepseekApiKey.isEmpty()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "请先设置DeepSeek API Key", Toast.LENGTH_LONG).show()
            }
            return
        }

        // Check if the word is already in history
        val existingWordDetails = wordHistory.find { it.word.equals(word, ignoreCase = true) }
        if (existingWordDetails != null) {
            CoroutineScope(Dispatchers.Main).launch {
                Toast.makeText(this@MainActivity, "从本地缓存显示: $word", Toast.LENGTH_SHORT).show()
                val formattedText = formatWordDetails(existingWordDetails)
                textView.text = formattedText
                textView.movementMethod = LinkMovementMethod.getInstance()
                // Update current index to the found item
                currentIndex = wordHistory.indexOf(existingWordDetails)
                updateNavigationButtons()
            }
            return
        }

        // Show loading message
        textView.text = "正在生成..."
        textView.movementMethod = null // Disable movement method while loading

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val userPrompt = promptTemplate.replace("{word}", word)

                // Escape strings for JSON
                val escapedSystemPrompt = gson.toJson(llmSystemPrompt)
                val escapedUserPrompt = gson.toJson(userPrompt)

                val jsonBody = """
                    {
                        "model": "deepseek-chat",
                        "response_format": { "type": "json_object" },
                        "messages": [
                            {"role": "system", "content": $escapedSystemPrompt},
                            {"role": "user", "content": $escapedUserPrompt}
                        ],
                        "stream": false
                    }
                """.trimIndent()

                val requestBody = jsonBody.toRequestBody(mediaType)

                val request = Request.Builder()
                    .url(API_URL)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer $deepseekApiKey")
                    .post(requestBody)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string()
                        throw IOException("Unexpected code ${response.code}\n$errorBody")
                    }

                    val responseBody = response.body?.string()
                    if (responseBody == null) {
                        throw IOException("Empty response body")
                    }

                    val apiResponse = gson.fromJson(responseBody, DeepSeekResponse::class.java)
                    val content = apiResponse.choices.firstOrNull()?.message?.content

                    if (content == null) {
                        throw IOException("No content in response")
                    }

                    // Parse the content string as JSON into our WordDetails data class
                    val parsedWordDetails = gson.fromJson(content, WordDetails::class.java)
                    // Create a new WordDetails object with the original word
                    val wordDetailsWithWord = parsedWordDetails.copy(word = word)
                    val formattedText = formatWordDetails(wordDetailsWithWord)

                    withContext(Dispatchers.Main) {
                        // If we are not at the end of the history, clear subsequent entries
                        if (currentIndex != wordHistory.size - 1) {
                            wordHistory.subList(currentIndex + 1, wordHistory.size).clear()
                        }
                        wordHistory.add(wordDetailsWithWord)
                        currentIndex = wordHistory.size - 1

                        textView.text = formattedText
                        textView.movementMethod = LinkMovementMethod.getInstance() // Enable clicking on links
                        updateNavigationButtons()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "请求失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
                e.printStackTrace()
            }
        }
    }

    private fun formatWordDetails(details: WordDetails): CharSequence {
        val sentenceText = details.sentence?.takeIf { it.isNotEmpty() } ?: "无"
        val sentenceTranslationText = details.sentence_translation?.takeIf { it.isNotEmpty() } ?: "无"
        val sentenceGrammarText = details.sentence_grammar?.takeIf { it.isNotEmpty() } ?: "无"
        val phoneticsText = details.phonetics?.takeIf { it.isNotEmpty() } ?: "无"
        val translationsText = details.translations?.takeIf { it.isNotEmpty() }?.joinToString("\n") {
            val pos = it.partOfSpeech?.takeIf { p -> p.isNotEmpty() } ?: ""
            val def = it.definition?.takeIf { d -> d.isNotEmpty() } ?: "无"
            "  - $pos $def"
        } ?: "无"

        val wordText = details.word?.takeIf { it.isNotEmpty() } ?: "无"

        val fullText = "单词: $wordText\n\n例句: $sentenceText\n\n音标: $phoneticsText\n\n词义:\n$translationsText\n\n例句翻译: $sentenceTranslationText\n\n语法说明: $sentenceGrammarText"

        val spannableString = SpannableString(fullText)

        if (sentenceText != "无") {
            val sentencePrefix = "例句: "
            val sentenceStartIndex = fullText.indexOf(sentenceText)
            val sentenceEndIndex = sentenceStartIndex + sentenceText.length

            // Regex to find words
            val wordRegex = "\\b\\w+\\b".toRegex()
            wordRegex.findAll(sentenceText).forEach { matchResult ->
                val word = matchResult.value
                val start = sentenceStartIndex + matchResult.range.first
                val end = sentenceStartIndex + matchResult.range.last + 1

                val clickableSpan = object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        wordInput.setText(word)
                        CoroutineScope(Dispatchers.Main).launch {
                            playWordAudio(word)
                            generateSentence(word, sentenceOutputTextView)
                        }
                    }

                    override fun updateDrawState(ds: TextPaint) {
                        super.updateDrawState(ds)
                        ds.isUnderlineText = true // Make word look like a link
                    }
                }
                spannableString.setSpan(clickableSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        return spannableString
    }

    private fun displayCurrentWordDetails() {
        if (currentIndex >= 0 && currentIndex < wordHistory.size) {
            val currentDetails = wordHistory[currentIndex]
            val formattedText = formatWordDetails(currentDetails)
            sentenceOutputTextView.text = formattedText
            sentenceOutputTextView.movementMethod = LinkMovementMethod.getInstance()
            // Update the word input box with the current word
            wordInput.setText(currentDetails.word)
        }
    }

    private fun updateNavigationButtons() {
        prevButton.isEnabled = currentIndex > 0
        nextButton.isEnabled = currentIndex < wordHistory.size - 1
    }

    // Data classes for the overall API response structure
    data class DeepSeekResponse(val choices: List<Choice>)
    data class Choice(val message: Message)
    data class Message(val role: String, val content: String)

    // Data classes for the JSON object within the 'content' field
    data class WordDetails(
        val word: String?, // Add this field
        val sentence: String?,
        val sentence_translation: String?,
        val sentence_grammar: String?,
        val phonetics: String?,
        val translations: List<TranslationDetail>?
    )

    data class TranslationDetail(
        val partOfSpeech: String?,
        val definition: String?
    )

    private suspend fun playWordAudio(word: String) {
        try {
            val audioFile = getAudioFile(word)

            if (audioFile.exists()) {
                // Play from local cache
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "从本地缓存播放: $word", Toast.LENGTH_SHORT).show()
                }
                playAudioFromFile(audioFile)
            } else {
                // Download and then play
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "下载并播放: $word", Toast.LENGTH_SHORT).show()
                }
                downloadAndPlayAudio(word, audioFile)
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "播放音频失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
            e.printStackTrace()
        }
    }

    private fun getAudioFile(word: String): File {
        val fileName = "${word.replace("[^a-zA-Z0-9]".toRegex(), "_")}.mp3"
        return File(cacheDir, fileName)
    }

    override fun onDestroy() {
        super.onDestroy()
        currentMediaPlayer?.release()
        currentMediaPlayer = null
    }

    private suspend fun playAudioFromFile(file: File) {
        withContext(Dispatchers.Main) {
            currentMediaPlayer?.release() // Release any previous player
            currentMediaPlayer = MediaPlayer().apply {
                try {
                    setDataSource(file.absolutePath)
                    setOnPreparedListener {
                        it.start()
                    }
                    setOnCompletionListener {
                        it.release()
                        currentMediaPlayer = null // Clear reference after completion
                    }
                    setOnErrorListener { mp, _, _ ->
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "播放本地音频失败", Toast.LENGTH_SHORT).show()
                        }
                        mp.release()
                        currentMediaPlayer = null // Clear reference on error
                        true
                    }
                    prepareAsync()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "准备本地音频失败: ${e.message}", Toast.LENGTH_LONG).show()
                    release() // Release on error during setup
                    currentMediaPlayer = null
                    e.printStackTrace()
                }
            }
        }
    }

    private suspend fun downloadAndPlayAudio(word: String, outputFile: File) {
        val encodedWord = URLEncoder.encode(word, "UTF-8")
        val audioUrl = "https://dict.youdao.com/dictvoice?audio=$encodedWord&type=2"

        val request = Request.Builder().url(audioUrl).build()

        withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("Unexpected code ${response.code}")
                    }

                    response.body?.byteStream()?.use { inputStream ->
                        FileOutputStream(outputFile).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        playAudioFromFile(outputFile)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "下载音频失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
                e.printStackTrace()
            }
        }
    }
}
