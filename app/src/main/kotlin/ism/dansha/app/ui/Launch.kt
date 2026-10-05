package ism.dansha.app.ui

/** ทางลัดจากนอกแอพ (กดค้างไอคอน / ปุ่มใน Quick Panel) → action ของ Intent */
enum class Launch(val action: String) {
    ADD_EXPENSE("ism.dansha.app.ADD_EXPENSE"),
    ADD_INCOME("ism.dansha.app.ADD_INCOME"),
    ADD_TRANSFER("ism.dansha.app.ADD_TRANSFER"),
    TRANSACTIONS("ism.dansha.app.TRANSACTIONS"),
    PLAN("ism.dansha.app.PLAN");

    companion object {
        fun fromAction(action: String?): Launch? = entries.firstOrNull { it.action == action }
    }
}
