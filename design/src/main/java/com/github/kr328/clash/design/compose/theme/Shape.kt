package com.github.kr328.clash.design.compose.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Скругления Clod Clash.
 *
 * До этого радиусы расходились: карточки брали умолчание Material (12 dp), строка
 * узла задавала свои 12 dp прямо в модификаторе, логотип в шапке — 9 dp, бейджи и
 * переключатель групп — 50 %. На одном экране это читается как три разных набора
 * элементов, хотя это один список.
 *
 * Теперь у всего, что является карточкой или строкой списка, радиус один — 16 dp.
 * Он задан ролью `medium`: её берёт `CardDefaults.shape`, поэтому отдельно править
 * каждую карточку не нужно. Роли `small` и `large` оставлены материаловскими:
 * на них сидят чипы и нижние шторки, и трогать их незачем.
 *
 * Полностью круглыми (50 %) остаются только бейджи и кнопки — там скругление
 * не про форму карточки, а про сам элемент.
 */
internal val ClodShapes: Shapes = Shapes(
    medium = RoundedCornerShape(16.dp),
)

/** Радиус строки списка. Тот же, что у карточки: строка узла — это карточка. */
val ClodRowCorner = 16.dp
