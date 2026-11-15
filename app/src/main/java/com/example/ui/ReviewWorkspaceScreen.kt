package com.example.ui

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

data class FidelSeries(
    val baseName: String,
    val ethiopicName: String,
    val consonantSound: String,
    val forms: List<String>, // 7 forms: Ge'ez, Ka'eb, Sales, Rabe, Hames, Sads, Sabe
    val exampleAmharic: String,
    val exampleMeaning: String,
    val isVerifiedClassicalEtymology: Boolean = true,
    val etymologyNote: String = if (isVerifiedClassicalEtymology) "Classical Semitic Ge'ez root" else "Unverified etymology (Modern Amharic palatal consonant innovation)"
)

data class StudyFlashcard(
    val id: Int,
    val prompt: String,
    val subtitle: String,
    val answer: String,
    val details: String,
    val category: String
)

data class ReviewQuizQuestion(
    val prompt: String,
    val options: List<String>,
    val correctIndex: Int,
    val explanation: String
)

@Composable
fun ReviewWorkspaceScreen(viewModel: WorkspaceViewModel) {
    var selectedTab by remember { mutableStateOf(0) } // 0: Fidel Matrix, 1: Flashcards, 2: Quiz

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Sub-navigation tab bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                label = { Text("🔤 Fidel Matrix", fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp)
            )
            FilterChip(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                label = { Text("🃏 Flashcards", fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp)
            )
            FilterChip(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                label = { Text("🎯 Practice Quiz", fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp)
            )
        }

        when (selectedTab) {
            0 -> FidelExplorerSection()
            1 -> FlashcardsStudySection(viewModel)
            2 -> ReviewQuizSection(viewModel)
        }
    }
}

// ----------------------------------------------------
// 1. GE'EZ FIDEL EXPLORER MATRIX
// Standard Ethiopic Order Names & Etymology Verification
// ----------------------------------------------------
@Composable
fun FidelExplorerSection() {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val fidelList = remember {
        listOf(
            FidelSeries("Hoi", "ሆይ", "h", listOf("ሀ", "ሁ", "ሂ", "ሃ", "ሄ", "ህ", "ሆ"), "ሀገር", "Country / Nation", true, "Classical Semitic root: Proto-Semitic *h (Leslau 1995; Dillmann 1899)"),
            FidelSeries("Lawi", "ላዊ", "l", listOf("ለ", "ሉ", "ሊ", "ላ", "ሌ", "ል", "ሎ"), "ልብ", "Heart", true, "Classical Semitic root: Proto-Semitic *l (Leslau 1995; Dillmann 1899)"),
            FidelSeries("Hawt", "ሐውት", "hh", listOf("ሐ", "ሑ", "ሒ", "ሓ", "ሔ", "ሕ", "ሖ"), "ሐር", "Silk", true, "Classical Semitic pharyngeal root: Proto-Semitic *ḥ (Leslau 1995; Dillmann 1899)"),
            FidelSeries("May", "ማይ", "m", listOf("መ", "ሙ", "ሚ", "ማ", "ሜ", "ም", "ሞ"), "መጽሐፍ", "Book", true, "Classical Semitic root: Proto-Semitic *m (Leslau 1995; Dillmann 1899)"),
            FidelSeries("Sawt", "ሠውት", "s", listOf("ሠ", "ሡ", "ሢ", "ሣ", "ሤ", "ሥ", "ሦ"), "ሦስት", "Three", true, "Classical Semitic lateral fricative root: Proto-Semitic *ś (Leslau 1995; Dillmann 1899)"),
            FidelSeries("Re'es", "ርእስ", "r", listOf("ረ", "ሩ", "ሪ", "ራ", "ሬ", "ር", "ሮ"), "ሩቅ", "Far / Distant", true, "Classical Semitic root: Proto-Semitic *r (Leslau 1995; Dillmann 1899)"),
            FidelSeries("Sat", "ሳት", "s", listOf("ሰ", "ሱ", "ሲ", "ሳ", "ሴ", "ስ", "ሶ"), "ሰላም", "Peace / Greetings", true, "Classical Semitic root: Proto-Semitic *s (Leslau 1995; Dillmann 1899)"),
            FidelSeries("Sha", "ሻ", "sh", listOf("ሸ", "ሹ", "ሺ", "ሻ", "ሼ", "ሽ", "ሾ"), "ሻይ", "Tea", false, "Unverified, needs native-speaker or linguist review (Amharic palatalization from Sat)"),
            FidelSeries("Qaf", "ቃፍ", "q", listOf("ቀ", "ቁ", "ቂ", "ቃ", "ቄ", "ቅ", "ቆ"), "ቀይ", "Red", true, "Classical Semitic uvular/ejective root: Proto-Semitic *q (Leslau 1995; Dillmann 1899)"),
            FidelSeries("Bet", "ቤት", "b", listOf("በ", "ቡ", "ቢ", "ባ", "ቤ", "ብ", "ቦ"), "ቤት", "House / Home", true, "Classical Semitic root: Proto-Semitic *b (Leslau 1995; Dillmann 1899)"),
            FidelSeries("Taw", "ተው", "t", listOf("ተ", "ቱ", "ቲ", "ታ", "ቴ", "ት", "ቶ"), "ትምህርት", "Education / Study", true, "Classical Semitic root: Proto-Semitic *t (Leslau 1995; Dillmann 1899)"),
            FidelSeries("Chaw", "ቸ", "ch", listOf("ቸ", "ቹ", "ቺ", "ቻ", "ቼ", "ች", "ቾ"), "ቸር", "Kind / Benevolent", false, "Unverified, needs native-speaker or linguist review (Amharic palatalization from Taw)"),
            FidelSeries("Harm", "ኀርም", "h", listOf("ኀ", "ኁ", "ኂ", "ኃ", "ኄ", "ኅ", "ኆ"), "ኀይል", "Power / Strength", true, "Classical Semitic velar fricative root: Proto-Semitic *ḫ (Leslau 1995; Dillmann 1899)"),
            FidelSeries("Nahas", "ነሐስ", "n", listOf("ነ", "ኑ", "ኒ", "ና", "ኔ", "ን", "ኖ"), "ነጭ", "White", true, "Classical Semitic root: Proto-Semitic *n (Leslau 1995; Dillmann 1899)"),
            FidelSeries("Nya", "ኘ", "ny", listOf("ኘ", "ኙ", "ኚ", "ኛ", "ኜ", "ኝ", "ኞ"), "እኛ", "We / Us", false, "Unverified, needs native-speaker or linguist review (Amharic palatalization from Nahas)"),
            FidelSeries("Alf", "አልፍ", "a", listOf("አ", "ኡ", "ኢ", "ኣ", "ኤ", "እ", "ኦ"), "አባት", "Father", true, "Classical Semitic glottal stop root: Proto-Semitic *ʔ (Leslau 1995; Dillmann 1899)"),
            FidelSeries("Kaf", "ካፍ", "k", listOf("ከ", "ኩ", "ኪ", "ካ", "ኬ", "ክ", "ኮ"), "ክረምት", "Rainy Season", true, "Classical Semitic root: Proto-Semitic *k (Leslau 1995; Dillmann 1899)"),
            FidelSeries("Kha", "ኻፍ", "kh", listOf("ኸ", "ኹ", "ኺ", "ኻ", "ኼ", "ኽ", "ኾ"), "ኸታታ", "Follower / Tail", false, "Unverified, needs native-speaker or linguist review (Spirantized post-classical variant of Kaf)"),
            FidelSeries("Waw", "ዋው", "w", listOf("ወ", "ዉ", "ዊ", "ዋ", "ዌ", "ው", "ዎ"), "ውሃ", "Water", true, "Classical Semitic root: Proto-Semitic *w (Leslau 1995; Dillmann 1899)"),
            FidelSeries("Ayn", "ዐይን", "'a", listOf("ዐ", "ዑ", "ዒ", "ዓ", "ዔ", "ዕ", "ዖ"), "ዓመት", "Year", true, "Classical Semitic voiced pharyngeal root: Proto-Semitic *ʕ (Leslau 1995; Dillmann 1899)"),
            FidelSeries("Zay", "ዛይ", "z", listOf("ዘ", "ዙ", "ዚ", "ዛ", "ዜ", "ዝ", "ዞ"), "ዜማ", "Melody / Chant", true, "Classical Semitic root: Proto-Semitic *z (Leslau 1995; Dillmann 1899)"),
            FidelSeries("Zha", "ዠ", "zh", listOf("ዠ", "ዡ", "ዢ", "ዣ", "ዤ", "ዥ", "ዦ"), "ዠመረ", "Began / Started", false, "Unverified, needs native-speaker or linguist review (Amharic palatalization from Zay)"),
            FidelSeries("Yaman", "የማን", "y", listOf("የ", "ዩ", "ዪ", "ያ", "ዬ", "ይ", "ዮ"), "ቀን", "Day", true, "Classical Semitic root: Proto-Semitic *y (Leslau 1995; Dillmann 1899)"),
            FidelSeries("Dent", "ድንት", "d", listOf("ደ", "ዱ", "ዲ", "ዳ", "ዴ", "ድ", "ዶ"), "ደስተኛ", "Happy", true, "Classical Semitic root: Proto-Semitic *d (Leslau 1995; Dillmann 1899)"),
            FidelSeries("Jent", "ጀ", "j", listOf("ጀ", "ጁ", "ጂ", "ጃ", "ጄ", "ጅ", "ጆ"), "ጀግና", "Brave / Hero", false, "Unverified, needs native-speaker or linguist review (Amharic palatalization from Dent)"),
            FidelSeries("Gaml", "ገምል", "g", listOf("ገ", "ጉ", "ጊ", "ጋ", "ጌ", "ግ", "ጎ"), "ገበታ", "Dining Table", true, "Classical Semitic root: Proto-Semitic *g (Leslau 1995; Dillmann 1899)"),
            FidelSeries("Tait", "ጣይት", "t'", listOf("ጠ", "ጡ", "ጢ", "ጣ", "ጤ", "ጥ", "ጦ"), "ጥዋት", "Morning", true, "Classical Semitic ejective root: Proto-Semitic *ṭ (Leslau 1995; Dillmann 1899)"),
            FidelSeries("Tchait", "ጨ", "ch'", listOf("ጨ", "ጩ", "ጪ", "ጫ", "ጬ", "ጭ", "ጮ"), "ጨረቃ", "Moon", false, "Unverified, needs native-speaker or linguist review (Amharic palatalization from Tait)"),
            FidelSeries("Pait", "ጰይት", "p'", listOf("ጰ", "ጱ", "ጲ", "ጳ", "ጴ", "ጵ", "ጶ"), "ጳጳስ", "Bishop / Patriarch", true, "Classical Semitic ejective root: Ancient Greek loan tradition in Ge'ez (Leslau 1995)"),
            FidelSeries("Tsaday", "ጸደይ", "ts", listOf("ጸ", "ጹ", "ጺ", "ጻ", "ጼ", "ጽ", "ጾ"), "ጸሎት", "Prayer", true, "Classical Semitic ejective affricate root: Proto-Semitic *ṣ (Leslau 1995; Dillmann 1899)"),
            FidelSeries("Tsappa", "ፀጳ", "ts'", listOf("ፀ", "ፁ", "ፂ", "ፃ", "ፄ", "ፅ", "ፆ"), "ፀሐይ", "Sun", true, "Classical Semitic emphatic lateral root: Proto-Semitic *ṣ́ (Leslau 1995; Dillmann 1899)"),
            FidelSeries("Af", "አፍ", "f", listOf("ፈ", "ፉ", "ፊ", "ፋ", "ፌ", "ፍ", "ፎ"), "ፈረስ", "Horse", true, "Classical Semitic root: Proto-Semitic *p/*f (Leslau 1995; Dillmann 1899)"),
            FidelSeries("Psa", "ፐ", "p", listOf("ፐ", "ፑ", "ፒ", "ፓ", "ፔ", "ፕ", "ፖ"), "ፖስታ", "Post / Mail", false, "Unverified, needs native-speaker or linguist review (Modern European loanword addition)")
        )
    }

    // Standard Ethiopic order names in official Ge'ez liturgy and linguistics
    val standardOrderNames = listOf(
        "1st: ግዕዝ (Ge'ez)",
        "2nd: ካዕብ (Ka'eb)",
        "3rd: ሣልስ (Sales)",
        "4th: ራብዕ (Rabe)",
        "5th: ኃምስ (Hames)",
        "6th: ሳድስ (Sads)",
        "7th: ሳብዕ (Sabe)"
    )
    val vowelSounds = listOf("ä / e", "u", "i", "a", "ē", "ə / -", "o")

    var selectedFidel by remember { mutableStateOf<Pair<FidelSeries, Int>?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // Guide Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(16.dp)
        ) {
            val citedCount = remember(fidelList) { fidelList.count { it.isVerifiedClassicalEtymology } }
            val unverifiedCount = remember(fidelList) { fidelList.count { !it.isVerifiedClassicalEtymology } }
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "ፊደል (Ge'ez Abugida Matrix) — ${fidelList.size} Series × 7 Orders (${fidelList.size * 7} Glyphs)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Standard 7 orders: ግዕዝ, ካዕብ, ሣልስ, ራብዕ, ኃምስ, ሳድስ, ሳብዕ • Transliteration: Ethiopian Semitic / Leslau (ä, u, i, a, ē, ə, o) • $citedCount classical series cited (Leslau 1995; Dillmann 1899), $unverifiedCount marked unverified. Tap any glyph to inspect.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Selected Character Details Card
        selectedFidel?.let { (series, orderIdx) ->
            val char = series.forms[orderIdx]
            val orderLabel = standardOrderNames[orderIdx]
            val vowel = vowelSounds[orderIdx]

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .testTag("fidel_detail_card")
                    .border(
                        BorderStroke(
                            1.5.dp,
                            if (series.isVerifiedClassicalEtymology) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                            else Color(0xFFF59E0B).copy(alpha = 0.6f)
                        ),
                        RoundedCornerShape(20.dp)
                    ),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(char, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${series.baseName} (${series.ethiopicName}) • $orderLabel",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Sound: /${series.consonantSound}$vowel/",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Sample: ${series.exampleAmharic} (${series.exampleMeaning})",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        IconButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(char))
                                Toast.makeText(context, "Copied '$char' to clipboard!", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text("📋", fontSize = 18.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Etymology indicator badge
                    Surface(
                        color = if (series.isVerifiedClassicalEtymology) Color(0xFF10B981).copy(alpha = 0.12f)
                        else Color(0xFFF59E0B).copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("fidel_etymology_badge")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = if (series.isVerifiedClassicalEtymology) "✓ Classical Ge'ez Root (Cited: Leslau 1995; Dillmann 1899)" else "⚠️ Unverified, needs native-speaker or linguist review",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (series.isVerifiedClassicalEtymology) Color(0xFF047857) else Color(0xFFB45309)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = series.etymologyNote,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Table Header (7 Standard Ethiopic Vowel Orders)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Root", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(46.dp))
            listOf("1: ግዕዝ", "2: ካዕብ", "3: ሣልስ", "4: ራብዕ", "5: ኃምስ", "6: ሳድስ", "7: ሳብዕ").forEach { name ->
                Text(
                    text = name,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(bottom = 6.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))

        // Matrix Lazy List
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(fidelList) { series ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Column(modifier = Modifier.width(46.dp)) {
                            Text(
                                text = series.baseName,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (series.isVerifiedClassicalEtymology) "✓ Ge'ez" else "⚠️ Unv.",
                                fontSize = 8.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (series.isVerifiedClassicalEtymology) Color(0xFF10B981) else Color(0xFFF59E0B)
                            )
                        }

                        series.forms.forEachIndexed { orderIdx, character ->
                            val isSelected = selectedFidel?.first == series && selectedFidel?.second == orderIdx
                            Surface(
                                onClick = { selectedFidel = Pair(series, orderIdx) },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("fidel_glyph_$character"),
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = character,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
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

// ----------------------------------------------------
// 2. FLASHCARDS STUDY SECTION (Persisted in Room Database)
// ----------------------------------------------------
@Composable
fun FlashcardsStudySection(viewModel: WorkspaceViewModel) {
    val cards = remember {
        listOf(
            StudyFlashcard(1, "ሰላም", "sälam", "Hello / Peace", "Traditional Ethiopian greeting across Amharic & Tigrinya.", "Greetings"),
            StudyFlashcard(2, "እንደምን አለህ?", "əndämən alläh?", "How are you? (to male)", "Addressing a male companion or colleague.", "Greetings"),
            StudyFlashcard(3, "እንደምን አለሽ?", "əndämən alläsh?", "How are you? (to female)", "Addressing a female companion or colleague.", "Greetings"),
            StudyFlashcard(4, "አመሰግናለሁ", "amäsäggənallähu", "Thank you", "Expression of gratitude.", "Courtesy"),
            StudyFlashcard(5, "ይቅርታ", "yəqərta", "Excuse me / Sorry", "Polite apology or getting someone's attention.", "Courtesy"),
            StudyFlashcard(6, "ውሃ", "wəha", "Water", "Essential noun.", "Daily Life"),
            StudyFlashcard(7, "እንጀራ", "ənjära", "Injera (Flatbread)", "Staple Ethiopian fermented teff sourdough flatbread.", "Food"),
            StudyFlashcard(8, "ቡና", "bunna", "Coffee", "Ethiopia is the birthplace of Coffea Arabica.", "Culture"),
            StudyFlashcard(9, "መጽሐፍ", "mätṣ'haf", "Book", "Root: ጸሐፈ (tsä-hä-fä = to write).", "Education"),
            StudyFlashcard(10, "ትምህርት ቤት", "təmhərt bet", "School", "Literally: House of Education.", "Education")
        )
    }

    val masteryMap by viewModel.flashcardMasteryMap.collectAsStateWithLifecycle()
    var currentIndex by remember { mutableStateOf(0) }
    var isFlipped by remember { mutableStateOf(false) }

    val currentCard = cards[currentIndex]
    val isMastered = masteryMap[currentCard.id] == true
    val totalMasteredCount = cards.count { masteryMap[it.id] == true }

    val rotation by animateFloatAsState(
        targetValue = if (isFlipped) 180f else 0f,
        animationSpec = tween(400),
        label = "flipRotation"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Progress Row with persistent mastery status from Room
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Card ${currentIndex + 1} of ${cards.size}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AssistChip(
                onClick = {},
                label = { Text("Mastered: $totalMasteredCount/${cards.size} (Room DB)", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                leadingIcon = { Text("⭐") },
                colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            )
        }

        LinearProgressIndicator(
            progress = { (currentIndex + 1).toFloat() / cards.size },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .height(6.dp)
                .clip(CircleShape),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Flip Flashcard with 3D Rotation Animation
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .graphicsLayer {
                    rotationY = rotation
                    cameraDistance = 12f * density
                }
                .clickable { isFlipped = !isFlipped },
            contentAlignment = Alignment.Center
        ) {
            if (rotation <= 90f) {
                // FRONT OF CARD
                Card(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(
                        2.dp,
                        if (isMastered) Color(0xFF10B981) else MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = currentCard.category.uppercase(),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (isMastered) {
                                Surface(
                                    color = Color(0xFF10B981).copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = "✓ MASTERED (IN ROOM)",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF047857),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        Text(
                            text = currentCard.prompt,
                            fontSize = 38.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = "/ ${currentCard.subtitle} /",
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.Medium
                        )

                        Spacer(modifier = Modifier.weight(1f))

                        Text(
                            text = "👆 Tap card to reveal English meaning",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                // BACK OF CARD
                Card(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { rotationY = 180f },
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)),
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "ENGLISH TRANSLATION",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Spacer(modifier = Modifier.weight(1f))

                        Text(
                            text = currentCard.answer,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = currentCard.details,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )

                        Spacer(modifier = Modifier.weight(1f))

                        Text(
                            text = "👆 Tap card to flip back",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Action Buttons Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = {
                    isFlipped = false
                    if (currentIndex > 0) currentIndex--
                },
                enabled = currentIndex > 0,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Previous")
            }

            Button(
                onClick = {
                    viewModel.toggleFlashcardMastery(currentCard.id)
                    isFlipped = false
                },
                modifier = Modifier
                    .weight(1.3f)
                    .height(48.dp)
                    .testTag("flashcard_mastered_button"),
                contentPadding = PaddingValues(horizontal = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isMastered) Color(0xFF6B7280) else Color(0xFF10B981)
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    text = if (isMastered) "Unmark" else "⭐ Mastered",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }

            Button(
                onClick = {
                    isFlipped = false
                    if (currentIndex < cards.size - 1) {
                        currentIndex++
                    } else {
                        currentIndex = 0
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Next")
            }
        }
    }
}

// ----------------------------------------------------
// 3. RETENTION QUIZ SECTION (Persisted in Room Database)
// ----------------------------------------------------
@Composable
fun ReviewQuizSection(viewModel: WorkspaceViewModel) {
    val quizQuestions = remember {
        listOf(
            ReviewQuizQuestion(
                prompt = "Which vowel order does the character 'ማ' represent in the Fidel 'መ' series?",
                options = listOf("1st: ግዕዝ (Ge'ez - ä)", "4th: ራብዕ (Rabe - a)", "6th: ሳድስ (Sads - ə)", "7th: ሳብዕ (Sabe - o)"),
                correctIndex = 1,
                explanation = "ማ is the 4th order (ራብዕ - Rabe) of the letter May (መ)."
            ),
            ReviewQuizQuestion(
                prompt = "What is the English translation of 'አመሰግናለሁ'?",
                options = listOf("Good morning", "Goodbye", "Thank you", "Please"),
                correctIndex = 2,
                explanation = "አመሰግናለሁ (amäsäggïnallähu) means 'Thank you' in Amharic."
            ),
            ReviewQuizQuestion(
                prompt = "How many vowel orders are there in the standard Ge'ez abugida system?",
                options = listOf("5 orders", "6 orders", "7 orders (ግዕዝ to ሳብዕ)", "8 orders"),
                correctIndex = 2,
                explanation = "The Ge'ez script consists of 7 standard Ethiopic vowel orders: ግዕዝ, ካዕብ, ሣልስ, ራብዕ, ኃምስ, ሳድስ, ሳብዕ."
            ),
            ReviewQuizQuestion(
                prompt = "Which of the following Amharic words translates to 'Water'?",
                options = listOf("ሻይ", "ውሃ", "ዳቦ", "ቡና"),
                correctIndex = 1,
                explanation = "ውሃ (wïha) translates to 'Water'."
            ),
            ReviewQuizQuestion(
                prompt = "What does the Amharic word 'ትምህርት ቤት' literally mean?",
                options = listOf("House of Peace", "House of Books", "House of Education", "House of Prayer"),
                correctIndex = 2,
                explanation = "ትምህርት (Education) + ቤት (House) = School (House of Education)."
            )
        )
    }

    val allScores by viewModel.allQuizScores.collectAsStateWithLifecycle()
    val bestScore by viewModel.bestQuizScore.collectAsStateWithLifecycle()

    var questionIdx by remember { mutableStateOf(0) }
    var selectedOption by remember { mutableStateOf<Int?>(null) }
    var score by remember { mutableStateOf(0) }
    var quizCompleted by remember { mutableStateOf(false) }

    val currentQ = quizQuestions[questionIdx]
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!quizCompleted) {
            // Header stats
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Question ${questionIdx + 1} of ${quizQuestions.size}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Score: $score (Best: ${bestScore ?: 0})",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            LinearProgressIndicator(
                progress = { (questionIdx + 1).toFloat() / quizQuestions.size },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .height(6.dp)
                    .clip(CircleShape),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Question Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = currentQ.prompt,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 22.sp
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    // Options list
                    currentQ.options.forEachIndexed { optIdx, optText ->
                        val isSelected = selectedOption == optIdx
                        val isAnswered = selectedOption != null
                        val isCorrect = optIdx == currentQ.correctIndex

                        val borderCol = when {
                            isAnswered && isCorrect -> Color(0xFF10B981)
                            isAnswered && isSelected && !isCorrect -> Color(0xFFEF4444)
                            else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                        }

                        val containerCol = when {
                            isAnswered && isCorrect -> Color(0xFF10B981).copy(alpha = 0.1f)
                            isAnswered && isSelected && !isCorrect -> Color(0xFFEF4444).copy(alpha = 0.1f)
                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        }

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .testTag("quiz_option_$optIdx")
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = !isAnswered) {
                                    selectedOption = optIdx
                                    if (optIdx == currentQ.correctIndex) {
                                        score++
                                    }
                                },
                            color = containerCol,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, borderCol)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${('A'.code + optIdx).toChar()}.",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = optText,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                if (isAnswered) {
                                    if (isCorrect) {
                                        Text("✓", color = Color(0xFF10B981), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    } else if (isSelected) {
                                        Text("✗", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    }
                                }
                            }
                        }
                    }

                    // Explanation Box
                    if (selectedOption != null) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = "💡 " + currentQ.explanation,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(10.dp),
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Next / Finish Question Button
            Button(
                onClick = {
                    if (questionIdx < quizQuestions.size - 1) {
                        questionIdx++
                        selectedOption = null
                    } else {
                        quizCompleted = true
                        viewModel.recordQuizResult(score, quizQuestions.size)
                    }
                },
                enabled = selectedOption != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("quiz_next_button"),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    text = if (questionIdx < quizQuestions.size - 1) "Next Question →" else "Finish & Save Score to Room",
                    fontWeight = FontWeight.Bold
                )
            }
        } else {
            // Quiz Complete Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("🎉", fontSize = 48.sp)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Practice Quiz Complete!", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "Score: $score / ${quizQuestions.size} (${(score * 100) / quizQuestions.size}%)",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Surface(
                        color = Color(0xFF10B981).copy(alpha = 0.12f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "✓ Persisted in Room Database (${allScores.size} total attempts recorded)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF047857),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    Button(
                        onClick = {
                            questionIdx = 0
                            selectedOption = null
                            score = 0
                            quizCompleted = false
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("Retake Quiz", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
