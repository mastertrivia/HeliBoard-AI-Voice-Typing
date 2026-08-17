// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.DeshNativeWordStore
import helium314.keyboard.latin.R
import helium314.keyboard.settings.SearchScreen
import helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog

/**
 * Manual add/edit/delete screen for the Desh Hindi user-native words
 * (Desh's UserNativeWordListActivity + UserNativeWordEntryActivity).
 *
 * Desh does NOT auto-learn: words are entered by hand here and merged at
 * max score into the desh_hindi suggestions (see DeshNativeWordStore).
 */
@Composable
fun DeshNativeWordsScreen(
    onClickBack: () -> Unit,
) {
    var words by remember { mutableStateOf(DeshNativeWordStore.all()) }
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }
    SearchScreen(
        onClickBack = onClickBack,
        title = { Text(stringResource(R.string.desh_native_words_title)) },
        filteredItems = { term ->
            if (term.isBlank()) words
            else words.filter { it.first.contains(term, true) || it.second.contains(term, true) }
        },
        itemContent = { (word, value) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { editing = word to value }
                    .padding(vertical = 6.dp, horizontal = 16.dp)
            ) {
                Column {
                    Text(word, style = MaterialTheme.typography.bodyLarge)
                    if (value.isNotBlank())
                        Text(
                            value,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                }
                Icon(
                    painterResource(R.drawable.ic_edit),
                    stringResource(R.string.desh_native_words_edit_title)
                )
            }
        }
    )
    if (editing != null)
        EditNativeWordDialog(editing!!) {
            editing = null
            words = DeshNativeWordStore.all()
        }
    ExtendedFloatingActionButton(
        onClick = { editing = "" to "" },
        text = { Text(stringResource(R.string.desh_native_words_add)) },
        icon = { Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.desh_native_words_add)) },
        modifier = Modifier.wrapContentSize(Alignment.BottomEnd).padding(all = 12.dp)
            .then(Modifier.safeDrawingPadding())
    )
}

@Composable
private fun EditNativeWordDialog(initial: Pair<String, String>, onDismissRequest: () -> Unit) {
    var word by remember { mutableStateOf(initial.first) }
    var value by remember { mutableStateOf(initial.second) }
    ThreeButtonAlertDialog(
        onDismissRequest = onDismissRequest,
        onConfirmed = {
            // editing an existing word and the key changed: drop the old entry
            if (initial.first.isNotEmpty() && word != initial.first)
                DeshNativeWordStore.remove(initial.first)
            DeshNativeWordStore.add(word, value)
            onDismissRequest()
        },
        checkOk = { word.isNotBlank() },
        confirmButtonText = stringResource(R.string.save),
        neutralButtonText = stringResource(R.string.delete),
        onNeutral = {
            DeshNativeWordStore.remove(initial.first)
            onDismissRequest()
        },
        title = { Text(stringResource(R.string.desh_native_words_edit_title)) },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    value = word,
                    onValueChange = { word = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.desh_native_words_word_hint)) }
                )
                TextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.desh_native_words_value_hint)) }
                )
            }
        }
    )
}
