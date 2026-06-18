package com.aritr.rova.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aritr.rova.ui.theme.ThemeSelection
import com.aritr.rova.ui.theme.rovaPalettes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSwatchSheet(
    title: String,
    options: List<ThemeSelection>,
    selected: ThemeSelection,
    optionLabel: (ThemeSelection) -> String,
    onPick: (ThemeSelection) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            title,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            fontWeight = FontWeight.SemiBold,
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(options) { sel ->
                ThemeSwatchTile(sel, sel == selected, optionLabel(sel)) { onPick(sel); onDismiss() }
            }
        }
    }
}

@Composable
private fun ThemeSwatchTile(
    sel: ThemeSelection,
    isSelected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    // Follow-System has no own palette — preview the concrete dark default (Aurora).
    val p = rovaPalettes.getValue(sel.resolveConcrete(systemDark = true))
    val ring = if (isSelected) p.accent else p.edge
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(p.background)
            .border(if (isSelected) 2.dp else 1.dp, ring, RoundedCornerShape(16.dp))
            .selectable(selected = isSelected, onClick = onClick, role = Role.RadioButton)
            .padding(12.dp)
            .heightIn(min = 72.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 24.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Brush.horizontalGradient(listOf(p.accent, p.accent2))),
        )
        Text(label, color = p.textHigh, fontWeight = FontWeight.Medium)
    }
}
