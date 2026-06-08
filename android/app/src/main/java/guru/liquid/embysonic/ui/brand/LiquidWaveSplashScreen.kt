package guru.liquid.embysonic.ui.brand

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

@Composable
fun LiquidWaveSplashScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0x1F4FC3F7), Color(0xFF0B1020)),
                    radius = 620f,
                ),
            )
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        LiquidWaveSplashLogo()
        Text(
            text = buildAnnotatedString {
                append("liquid")
                withStyle(SpanStyle(color = Color(0xFF4FC3F7), fontWeight = FontWeight.Bold)) {
                    append("Wave")
                }
            },
            style = MaterialTheme.typography.headlineMedium,
            color = Color(0xFFE3E7F0),
            modifier = Modifier.padding(top = 28.dp),
        )
    }
}
