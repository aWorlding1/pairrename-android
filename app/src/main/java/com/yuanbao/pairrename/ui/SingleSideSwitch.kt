@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.yuanbao.pairrename.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yuanbao.pairrename.model.Side

/** 单栏页顶部：切换看左还是看右（替代原来的两个底栏 Tab）。 */
@Composable
fun SingleSideSwitch(
    side: Side,
    onChange: (Side) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        SegmentedButton(
            selected = side == Side.LEFT,
            onClick = { onChange(Side.LEFT) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
        ) { Text("左栏") }
        SegmentedButton(
            selected = side == Side.RIGHT,
            onClick = { onChange(Side.RIGHT) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
        ) { Text("右栏") }
    }
}
