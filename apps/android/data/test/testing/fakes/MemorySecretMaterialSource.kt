package com.lomo.data.testing.fakes

import com.lomo.data.engine.sync.SecretMaterialSource

/** In-memory secret material for worker/facade tests; empty means "no material present". */
class MemorySecretMaterialSource(
    private val secrets: MutableMap<String, ByteArray> = mutableMapOf(),
) : SecretMaterialSource {
    override fun readSecretBytes(fieldKey: String): ByteArray? = secrets[fieldKey]?.copyOf()

    override fun hasMaterial(fieldKey: String): Boolean = secrets[fieldKey]?.isNotEmpty() == true

    fun put(
        fieldKey: String,
        value: ByteArray,
    ) {
        secrets[fieldKey] = value.copyOf()
    }
}
