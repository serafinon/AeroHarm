package harmonizer.core

/**
 * Catalogo delle scale suonabili, con compatibilità per qualità d'accordo.
 *
 * La scala NON è una proprietà dell'accordo: è una scelta del musicista,
 * per ogni accordo della progressione. Su Bb7 puoi suonare misolidia,
 * alterata, lidia dominante o blues, e sono armonizzazioni diverse.
 *
 * È la scala scelta a definire i GRADI su cui l'armonizzatore trasporta,
 * quindi ogni nota della scala ha un grado e l'armonia è sempre definita.
 */

data class Scale(
    val id: String,
    /** Nome mostrato. I modi della scala maggiore portano l'ordinale. */
    val name: String,
    val intervals: List<Int>,
    /** Qualità d'accordo su cui questa scala è proponibile. */
    val compatible: Set<ChordQuality>
) {
    val size: Int get() = intervals.size
    override fun toString(): String = name
}

object Scales {

    // ---- modi della scala maggiore -------------------------------------

    val IONICA = Scale(
        "ionica", "ionica (1° m.)", listOf(0, 2, 4, 5, 7, 9, 11),
        setOf(ChordQuality.MAJ, ChordQuality.MAJ7, ChordQuality.MAJ6)
    )
    val DORICA = Scale(
        "dorica", "dorica (2° m.)", listOf(0, 2, 3, 5, 7, 9, 10),
        setOf(ChordQuality.MIN, ChordQuality.MIN7, ChordQuality.MIN6)
    )
    val FRIGIA = Scale(
        "frigia", "frigia (3° m.)", listOf(0, 1, 3, 5, 7, 8, 10),
        setOf(ChordQuality.MIN, ChordQuality.MIN7)
    )
    val LIDIA = Scale(
        "lidia", "lidia (4° m.)", listOf(0, 2, 4, 6, 7, 9, 11),
        setOf(ChordQuality.MAJ, ChordQuality.MAJ7, ChordQuality.MAJ6)
    )
    val MISOLIDIA = Scale(
        "misolidia", "misolidia (5° m.)", listOf(0, 2, 4, 5, 7, 9, 10),
        setOf(ChordQuality.DOM7, ChordQuality.DOM7SUS4)
    )
    val EOLIA = Scale(
        "eolia", "eolia (6° m.)", listOf(0, 2, 3, 5, 7, 8, 10),
        setOf(ChordQuality.MIN, ChordQuality.MIN7)
    )
    val LOCRIA = Scale(
        "locria", "locria (7° m.)", listOf(0, 1, 3, 5, 6, 8, 10),
        setOf(ChordQuality.MIN7B5)
    )

    // ---- minore melodica e suoi modi ------------------------------------

    val MIN_MELODICA = Scale(
        "min_mel", "minore melodica", listOf(0, 2, 3, 5, 7, 9, 11),
        setOf(ChordQuality.MIN, ChordQuality.MIN6)
    )
    val LIDIA_DOMINANTE = Scale(
        "lidia_dom", "lidia dominante", listOf(0, 2, 4, 6, 7, 9, 10),
        setOf(ChordQuality.DOM7, ChordQuality.DOM7SHARP11)
    )
    val MISOLIDIA_B6 = Scale(
        "misolidia_b6", "misolidia b6", listOf(0, 2, 4, 5, 7, 8, 10),
        setOf(ChordQuality.DOM7)
    )
    val LOCRIA_DIESIS2 = Scale(
        "locria_2", "locria #2", listOf(0, 2, 3, 5, 6, 8, 10),
        setOf(ChordQuality.MIN7B5)
    )
    val ALTERATA = Scale(
        "alterata", "alterata", listOf(0, 1, 3, 4, 6, 8, 10),
        setOf(ChordQuality.DOM7ALT, ChordQuality.DOM7)
    )

    // ---- minore armonica ------------------------------------------------

    val MIN_ARMONICA = Scale(
        "min_arm", "minore armonica", listOf(0, 2, 3, 5, 7, 8, 11),
        setOf(ChordQuality.MIN)
    )

    // ---- simmetriche ----------------------------------------------------

    val SEMITONO_TONO = Scale(
        "sem_tono", "semitono-tono", listOf(0, 1, 3, 4, 6, 7, 9, 10),
        setOf(ChordQuality.DOM7, ChordQuality.DOM7ALT)
    )
    val TONO_SEMITONO = Scale(
        "tono_sem", "tono-semitono", listOf(0, 2, 3, 5, 6, 8, 9, 11),
        setOf(ChordQuality.DIM7)
    )
    val ESATONALE = Scale(
        "esatonale", "esatonale", listOf(0, 2, 4, 6, 8, 10),
        setOf(ChordQuality.DOM7ALT, ChordQuality.DOM7)
    )

    // ---- pentatoniche e blues -------------------------------------------

    val BLUES = Scale(
        "blues", "blues", listOf(0, 3, 5, 6, 7, 10),
        setOf(ChordQuality.DOM7, ChordQuality.MIN7)
    )
    val PENTA_MAG = Scale(
        "penta_mag", "pentatonica maggiore", listOf(0, 2, 4, 7, 9),
        setOf(ChordQuality.MAJ, ChordQuality.MAJ7, ChordQuality.MAJ6, ChordQuality.DOM7)
    )
    val PENTA_MIN = Scale(
        "penta_min", "pentatonica minore", listOf(0, 3, 5, 7, 10),
        setOf(ChordQuality.MIN, ChordQuality.MIN7, ChordQuality.DOM7)
    )

    val ALL = listOf(
        IONICA, DORICA, FRIGIA, LIDIA, MISOLIDIA, EOLIA, LOCRIA,
        MIN_MELODICA, LIDIA_DOMINANTE, MISOLIDIA_B6, LOCRIA_DIESIS2, ALTERATA,
        MIN_ARMONICA, SEMITONO_TONO, TONO_SEMITONO, ESATONALE,
        BLUES, PENTA_MAG, PENTA_MIN
    )

    /**
     * Scale proponibili su una qualità d'accordo, nell'ordine in cui vanno
     * mostrate: le prime tre o quattro sono quelle da mettere in evidenza.
     */
    fun compatibleWith(q: ChordQuality): List<Scale> {
        val order = preferenceOrder[q] ?: emptyList()
        val rest = ALL.filter { q in it.compatible && it !in order }
        return order + rest
    }

    /** Scala predefinita per una qualità: la prima della lista di preferenza. */
    fun defaultFor(q: ChordQuality): Scale =
        preferenceOrder[q]?.firstOrNull() ?: ALL.first { q in it.compatible }

    /**
     * Ordine di probabilità per ciascuna qualità. Le prime voci finiscono in
     * evidenza nella striscia di selezione.
     */
    private val preferenceOrder: Map<ChordQuality, List<Scale>> = mapOf(
        ChordQuality.MAJ to listOf(IONICA, LIDIA, PENTA_MAG),
        ChordQuality.MAJ7 to listOf(IONICA, LIDIA, PENTA_MAG),
        ChordQuality.MAJ6 to listOf(IONICA, LIDIA, PENTA_MAG),
        ChordQuality.DOM7 to listOf(MISOLIDIA, BLUES, LIDIA_DOMINANTE, ALTERATA, SEMITONO_TONO, MISOLIDIA_B6, PENTA_MAG, PENTA_MIN, ESATONALE),
        ChordQuality.DOM7SUS4 to listOf(MISOLIDIA),
        ChordQuality.DOM7SHARP11 to listOf(LIDIA_DOMINANTE),
        ChordQuality.DOM7ALT to listOf(ALTERATA, SEMITONO_TONO, ESATONALE),
        ChordQuality.MIN to listOf(EOLIA, DORICA, MIN_MELODICA, MIN_ARMONICA, FRIGIA, PENTA_MIN),
        ChordQuality.MIN7 to listOf(DORICA, EOLIA, FRIGIA, BLUES, PENTA_MIN),
        ChordQuality.MIN6 to listOf(DORICA, MIN_MELODICA),
        ChordQuality.MIN7B5 to listOf(LOCRIA, LOCRIA_DIESIS2),
        ChordQuality.DIM7 to listOf(TONO_SEMITONO)
    )
}
