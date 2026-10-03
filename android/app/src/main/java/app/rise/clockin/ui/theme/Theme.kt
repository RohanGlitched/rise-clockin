package app.rise.clockin.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.rise.clockin.R

/** Rise palette: the night sky, the dawn, the manila time card and its two inks. */
object Rise {
    val Night = Color(0xFF0E1430)
    val NightDeep = Color(0xFF080B1E)
    val Dusk = Color(0xFF2B2452)
    val Rose = Color(0xFFF2A0A1)
    val Apricot = Color(0xFFFFB877)
    val Sun = Color(0xFFFFD166)
    val SunDeep = Color(0xFFF4A52A)
    val Ivory = Color(0xFFF6F1E7)
    val Mist = Color(0xFFB9B6CC)
    val Haze = Color(0x33F6F1E7)
    val Glass = Color(0x1AF6F1E7)
    val Manila = Color(0xFFEDE3CC)
    val ManilaShade = Color(0xFFDCCFAF)
    val CardRule = Color(0xFFC9B993)
    val InkBlue = Color(0xFF1F2747)
    val InkRed = Color(0xFFC8322B)
    val Moss = Color(0xFF7FC8A9)
}

@OptIn(ExperimentalTextApi::class)
private fun bricolage(weight: Int, width: Float = 100f, opsz: Float = 24f) = Font(
    R.font.bricolage,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.width(width),
        FontVariation.Setting("opsz", opsz),
    ),
)

@OptIn(ExperimentalTextApi::class)
private fun doto(weight: Int, round: Float = 100f) = Font(
    R.font.doto,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.Setting("ROND", round),
    ),
)

/** UI text: Bricolage Grotesque. */
val Bricolage = FontFamily(bricolage(400), bricolage(500), bricolage(600), bricolage(700), bricolage(800))
val BricolageDisplay = FontFamily(bricolage(700, 86f, 96f), bricolage(800, 86f, 96f), bricolage(500, 86f, 96f))

/** Every clock reading in the app is set in Doto, the dot-matrix face of a time clock. */
val Doto = FontFamily(doto(500), doto(700), doto(900))

object RiseType {
    val clockHuge = TextStyle(fontFamily = Doto, fontWeight = FontWeight(900), fontSize = 96.sp, letterSpacing = (-0.02).em)
    val clockLarge = TextStyle(fontFamily = Doto, fontWeight = FontWeight(900), fontSize = 64.sp, letterSpacing = (-0.02).em)
    val clockMid = TextStyle(fontFamily = Doto, fontWeight = FontWeight(900), fontSize = 28.sp)
    val clockSmall = TextStyle(fontFamily = Doto, fontWeight = FontWeight(900), fontSize = 15.sp)
    /** Amounts (SKR, SOL, counts): Bricolage with tabular figures, never the clock face. */
    val amount = TextStyle(fontFamily = BricolageDisplay, fontWeight = FontWeight(800), fontSize = 26.sp, fontFeatureSettings = "tnum")
    val display = TextStyle(fontFamily = BricolageDisplay, fontWeight = FontWeight(800), fontSize = 42.sp, lineHeight = 44.sp, letterSpacing = (-0.012).em)
    val title = TextStyle(fontFamily = BricolageDisplay, fontWeight = FontWeight(700), fontSize = 28.sp, lineHeight = 31.sp, letterSpacing = (-0.005).em)
    val heading = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight(700), fontSize = 19.sp, lineHeight = 24.sp)
    val body = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight(400), fontSize = 16.sp, lineHeight = 23.sp)
    val bodyStrong = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight(600), fontSize = 16.sp, lineHeight = 23.sp)
    val small = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight(500), fontSize = 14.sp, lineHeight = 19.sp)
    val tiny = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight(500), fontSize = 12.sp, lineHeight = 16.sp)
    val button = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight(700), fontSize = 17.sp)
}

@Composable
fun RiseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Rise.Sun,
            onPrimary = Rise.Night,
            background = Rise.Night,
            onBackground = Rise.Ivory,
            surface = Rise.Night,
            onSurface = Rise.Ivory,
        ),
        content = content,
    )
}
