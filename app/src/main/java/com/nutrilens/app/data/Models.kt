package com.nutrilens.app.data

data class MealWithImages(
    val meal: MealEntity,
    val images: List<MealImageEntity>
)

data class DayData(
    val meals: List<MealWithImages>,
    val waterMl: Int,
    val weight: Double?,
    val workoutDone: Boolean
)