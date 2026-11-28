package com.componentvault.android.ui.screen

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.componentvault.android.R

internal data class VaultMenuAction(
    @StringRes val label: Int,
    @StringRes val hint: Int,
    @DrawableRes val icon: Int,
    val invoke: () -> Unit,
)

@Composable
internal fun VaultMenuPage(
    title: String,
    actions: List<VaultMenuAction>,
    onBack: () -> Unit,
    subtitle: String? = null,
    intro: String? = null,
) {
    BackHandler(onBack = onBack)
    val colors = MaterialTheme.colorScheme
    Scaffold(
        topBar = { VaultScreenHeader(title, caption = subtitle, onBack = onBack) },
        containerColor = colors.background,
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
        ) {
            intro?.let {
                Text(it, color = colors.onSurfaceVariant, fontSize = 13.sp, lineHeight = 22.sp,
                    modifier = Modifier.padding(bottom = 18.dp))
            }
            Surface(shape = RoundedCornerShape(24.dp), color = colors.surface) {
                Column {
                    actions.forEachIndexed { index, action ->
                        if (index > 0) HorizontalDivider(
                            Modifier.padding(start = 51.dp, end = 16.dp), color = colors.outlineVariant,
                        )
                        Row(
                            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = action.invoke)
                                .heightIn(min = 77.dp).padding(horizontal = 16.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(painterResource(action.icon), null, Modifier.size(21.dp), tint = colors.onSurfaceVariant)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(action.label), fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
                                Text(stringResource(action.hint), fontSize = 13.sp, lineHeight = 19.5.sp, color = colors.onSurfaceVariant)
                            }
                            Icon(painterResource(R.drawable.v2_chevron_right), null, Modifier.size(15.dp), tint = colors.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
