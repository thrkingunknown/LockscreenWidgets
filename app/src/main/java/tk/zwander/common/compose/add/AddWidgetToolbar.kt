package tk.zwander.common.compose.add

import androidx.compose.foundation.layout.Row
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import tk.zwander.lockscreenwidgets.R

@Composable
fun AddWidgetToolbar(
    filter: String?,
    onFilterChanged: (String?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        OutlinedTextField(
            value = filter ?: "",
            onValueChange = onFilterChanged,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            label = { Text(text = stringResource(id = R.string.search)) },
            trailingIcon = if (!filter.isNullOrBlank()) {
                {
                    IconButton(onClick = { onFilterChanged(null) }) {
                        Icon(
                            painter = painterResource(id = R.drawable.baseline_clear_24),
                            contentDescription = stringResource(id = R.string.clear)
                        )
                    }
                }
            } else null,
            leadingIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(id = R.drawable.baseline_arrow_back_24),
                        contentDescription = stringResource(id = R.string.back)
                    )
                }
            }
        )
    }
}