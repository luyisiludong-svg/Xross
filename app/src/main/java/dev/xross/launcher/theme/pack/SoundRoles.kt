package dev.xross.launcher.theme.pack

import androidx.compose.runtime.Immutable

/**
 * A pack's sounds resolved (Phase 2b.5a, Appendix A.9): the file and gain of every role, and the
 * roles that borrow a fallback's sample from the same pack ([links]), whose gain then follows the
 * fallback's wherever a variant, override or rule changes it.
 */
@Immutable
data class PackSounds(
    val sounds: Map<SoundRole, SoundSource>,
    val gains: Map<SoundRole, Float>,
    /** Role → the fallback role whose file and gain it uses (it has no file or gain of its own). */
    val links: Map<SoundRole, SoundRole> = emptyMap(),
    /** `audio.panAmount`, 0–1. */
    val panAmount: Float = DEFAULT_PAN_AMOUNT,
    /** `audio.cursorJitter`. */
    val cursorJitter: Boolean = false,
) {
    companion object {
        const val DEFAULT_PAN_AMOUNT = 0.35f
    }
}

/**
 * The `audio` block of a pack over [base] (the Default pack's resolved sounds; null while
 * resolving the Default pack itself). Each role plays, in order:
 *  1. the file the pack names for it in `audio.sounds`, or its canonical file ([SoundRole.file]);
 *  2. for a role with a [SoundRole.fallback], the fallback's own file in this pack;
 *  3. the Default pack's file for the role.
 * Its gain is the pack's `audio.soundGains` entry (clamped 0–1), else 1 for the pack's own file,
 * the fallback's gain for a borrowed one, or the Default pack's gain. [find] looks a file name up
 * in the pack (null when it isn't there). Unknown role keys and bad gains go to [problems].
 */
internal fun resolvePackSounds(
    audio: ThemeManifest.Audio?,
    base: PackSounds?,
    problems: MutableList<String>,
    find: (String) -> SoundSource?,
): PackSounds {
    val declaredSounds = audio?.sounds.orEmpty()
    for (key in declaredSounds.keys) if (SoundRole.byKey(key) == null) problems += unknownRole("audio.sounds", key)
    val declaredGains = gainsOf("audio", audio, problems)

    val own = HashMap<SoundRole, SoundSource>()
    for (role in SoundRole.entries) {
        val name = declaredSounds.entries.firstOrNull { SoundRole.byKey(it.key) == role }?.value ?: role.file
        find(name)?.let { own[role] = it }
    }
    val sounds = LinkedHashMap<SoundRole, SoundSource>()
    val gains = LinkedHashMap<SoundRole, Float>()
    val links = LinkedHashMap<SoundRole, SoundRole>()
    for (role in SoundRole.resolveOrder) {
        val gain = declaredGains[role]
        val fb = role.fallback
        val mine = own[role]
        when {
            mine != null -> {
                sounds[role] = mine
                gains[role] = gain ?: 1f
            }
            fb != null && own[fb] != null -> {
                sounds[role] = own.getValue(fb)
                gains[role] = gain ?: gains[fb] ?: 1f
                if (gain == null) links[role] = fb
            }
            else -> {
                base?.sounds?.get(role)?.let { sounds[role] = it }
                gains[role] = gain ?: base?.gains?.get(role) ?: 1f
                // Both this role and its fallback come from the Default pack: keep its link.
                if (gain == null) base?.links?.get(role)?.let { links[role] = it }
            }
        }
    }
    val pan = audio?.panAmount?.let { p ->
        if (p.isFinite()) p.coerceIn(0f, 1f) else null.also { problems += "audio.panAmount: $p isn't a number from 0 to 1" }
    }
    return PackSounds(
        sounds = SoundRole.entries.filter { it in sounds }.associateWith { sounds.getValue(it) },
        gains = SoundRole.entries.associateWith { gains[it] ?: 1f },
        links = links,
        panAmount = pan ?: base?.panAmount ?: PackSounds.DEFAULT_PAN_AMOUNT,
        cursorJitter = audio?.cursorJitter ?: base?.cursorJitter ?: false,
    )
}

/**
 * The `soundGains` of a variant's, override's or rule's `audio` block at [where], checked; null
 * when it sets none. Only gains and the music loop ([BgmRef.of]) change there: sound files,
 * panning and jitter are the pack's.
 */
internal fun resolveGainPatch(where: String, audio: ThemeManifest.Audio?, problems: MutableList<String>): Map<SoundRole, Float>? {
    if (audio == null) return null
    if (audio.sounds.isNotEmpty()) problems += "$where.audio.sounds: only soundGains and bgmFile can change here (sound files are the pack's)"
    if (audio.panAmount != null) problems += "$where.audio.panAmount: only soundGains and bgmFile can change here"
    if (audio.cursorJitter != null) problems += "$where.audio.cursorJitter: only soundGains and bgmFile can change here"
    return gainsOf("$where.audio", audio, problems).takeIf { it.isNotEmpty() }
}

/**
 * [base] gains with each patch's in order (the last one that names a role wins). A role linked
 * to a fallback ([PackSounds.links]) that no patch names follows its fallback's new gain.
 */
fun cascadeGains(
    base: Map<SoundRole, Float>,
    links: Map<SoundRole, SoundRole>,
    patches: List<Map<SoundRole, Float>>,
): Map<SoundRole, Float> {
    if (patches.isEmpty()) return base
    val out = LinkedHashMap(base)
    val named = HashSet<SoundRole>()
    for (p in patches) {
        out.putAll(p)
        named += p.keys
    }
    for ((role, fb) in links) if (role !in named && fb in named) out[role] = out.getValue(fb)
    return out
}

private fun gainsOf(where: String, audio: ThemeManifest.Audio?, problems: MutableList<String>): Map<SoundRole, Float> {
    val out = LinkedHashMap<SoundRole, Float>()
    for ((key, value) in audio?.soundGains.orEmpty()) {
        val role = SoundRole.byKey(key)
        when {
            role == null -> problems += unknownRole("$where.soundGains", key)
            !value.isFinite() -> problems += "$where.soundGains.$key: $value isn't a number from 0 to 1"
            role !in out -> out[role] = value.coerceIn(0f, 1f)
        }
    }
    return out
}

private fun unknownRole(where: String, key: String) =
    "$where.$key isn't a sound role (use ${SoundRole.entries.joinToString { it.key }})"
