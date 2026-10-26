package dev.drillbalancer.recorder

import dev.drillbalancer.protocol.RecOpcode
import dev.drillbalancer.protocol.RecProfile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The labels and recording settings of a session (meta.session in the dataset contract),
 * chosen once on the setup screen and remembered. Ids are the taxonomy's
 * (firmware/tools/dataset/dbdata/contract/taxonomy.v1.json, [Taxonomy]); "unknown" is
 * allowed everywhere, "other" with a text of up to 40 characters.
 */
@Serializable
data class SessionSetup(
    val material: String = Taxonomy.UNKNOWN,
    @SerialName("material_other") val materialOther: String = "",
    @SerialName("thickness_mm") val thicknessMm: Double? = null,
    val mode: String = "drill",
    @SerialName("bit_type") val bitType: String = Taxonomy.UNKNOWN,
    @SerialName("bit_other") val bitOther: String = "",
    @SerialName("bit_diameter_mm") val bitDiameterMm: Double? = null,
    @SerialName("drill_model") val drillModel: String = "",
    val gear: Int? = null,
    @SerialName("speed_setting") val speedSetting: Double? = null,
    val notes: String = "",
    @SerialName("mic_mask") val micMask: Int = RecOpcode.MIC_ALL,
    @SerialName("gain_db") val gainDb: Int = DEFAULT_GAIN_DB,
    @SerialName("max_s") val maxS: Int = RecOpcode.MAX_S_DEFAULT,
) {
    /** RAW448_16G keeps hammer blows (5-10 g) unclipped; everything else records RAW448. */
    val profile: RecProfile get() = if (mode == "hammer") RecProfile.RAW448_16G else RecProfile.RAW448

    /** The setup with every field cut to what the contract allows. */
    fun sanitized(): SessionSetup = copy(
        materialOther = materialOther.take(Taxonomy.OTHER_TEXT_MAX),
        bitOther = bitOther.take(Taxonomy.OTHER_TEXT_MAX),
        thicknessMm = thicknessMm?.takeIf { it > 0 && it.isFinite() },
        bitDiameterMm = bitDiameterMm?.takeIf { it > 0 && it <= BIT_DIAMETER_MAX_MM },
        drillModel = drillModel.take(DRILL_MODEL_MAX),
        gear = gear?.takeIf { it in 1..3 },
        speedSetting = speedSetting?.takeIf { it.isFinite() },
        notes = notes.take(NOTES_MAX),
        micMask = micMask and RecOpcode.MIC_ALL,
        gainDb = gainDb.coerceIn(0, RecOpcode.GAIN_MAX_DB),
        maxS = maxS.coerceIn(MAX_S_MIN, RecOpcode.MAX_S_LIMIT),
    )

    companion object {
        const val DEFAULT_GAIN_DB = 6       // ES7210 PGA, TODO(hw) in the firmware
        const val NOTES_MAX = 200
        const val DRILL_MODEL_MAX = 64
        const val BIT_DIAMETER_MAX_MM = 200.0
        const val MAX_S_MIN = 10

        /** Take length presets, seconds (the device's limit is 1200). */
        val MAX_S_PRESETS = listOf(60, 120, 300, 600, 1200)
    }
}

/** A taxonomy entry the setup screen offers. */
data class TaxonomyItem(val id: String, val label: String)

/** The recording-level ids of taxonomy.v1.json (TaxonomyTest compares them with the file). */
object Taxonomy {
    const val UNKNOWN = "unknown"
    const val OTHER = "other"
    const val OTHER_TEXT_MAX = 40
    const val VERSION = 1

    val MATERIALS = listOf(
        TaxonomyItem("wood", "Wood"),
        TaxonomyItem("wood/softwood", "Wood · softwood"),
        TaxonomyItem("wood/hardwood", "Wood · hardwood"),
        TaxonomyItem("wood/plywood", "Wood · plywood"),
        TaxonomyItem("wood/mdf_chipboard", "Wood · MDF / chipboard"),
        TaxonomyItem("metal", "Metal"),
        TaxonomyItem("metal/steel", "Metal · steel"),
        TaxonomyItem("metal/stainless", "Metal · stainless"),
        TaxonomyItem("metal/aluminium", "Metal · aluminium"),
        TaxonomyItem("metal/copper_brass", "Metal · copper / brass"),
        TaxonomyItem("masonry", "Masonry"),
        TaxonomyItem("masonry/concrete", "Masonry · concrete"),
        TaxonomyItem("masonry/brick", "Masonry · brick"),
        TaxonomyItem("masonry/aerated_concrete", "Masonry · aerated concrete"),
        TaxonomyItem("masonry/sand_lime", "Masonry · sand-lime"),
        TaxonomyItem("drywall", "Drywall"),
        TaxonomyItem("tile_stone", "Tile / stone"),
        TaxonomyItem("tile_stone/ceramic", "Tile · ceramic"),
        TaxonomyItem("tile_stone/porcelain", "Tile · porcelain"),
        TaxonomyItem("tile_stone/natural_stone", "Natural stone"),
        TaxonomyItem("plastic", "Plastic"),
        TaxonomyItem("composite", "Composite"),
    )

    val MODES = listOf(
        TaxonomyItem("drill", "Drill"),
        TaxonomyItem("hammer", "Hammer"),
        TaxonomyItem("screw", "Screw"),
    )

    val BIT_TYPES = listOf(
        TaxonomyItem("twist_hss", "Twist (HSS)"),
        TaxonomyItem("brad_point", "Brad point"),
        TaxonomyItem("spade", "Spade"),
        TaxonomyItem("forstner", "Forstner"),
        TaxonomyItem("masonry_cyl", "Masonry"),
        TaxonomyItem("masonry_sds", "Masonry SDS"),
        TaxonomyItem("tile_diamond", "Tile / diamond"),
        TaxonomyItem("step", "Step"),
        TaxonomyItem("hole_saw", "Hole saw"),
        TaxonomyItem("auger", "Auger"),
    )

    private val SPECIAL = listOf(TaxonomyItem(UNKNOWN, "Unknown"), TaxonomyItem(OTHER, "Other…"))

    /** What a picker offers: the field's ids, then unknown and other. */
    fun choices(items: List<TaxonomyItem>): List<TaxonomyItem> = items + SPECIAL

    fun label(items: List<TaxonomyItem>, id: String, other: String = ""): String = when (id) {
        OTHER -> other.ifBlank { "Other" }
        else -> choices(items).firstOrNull { it.id == id }?.label ?: id
    }
}
