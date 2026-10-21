package com.kavach.caller

import com.kavach.caller.domain.feature.FeatureId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural checks on the feature registry (F16). These catch the mistakes that
 * are easy to make when adding a feature: a self-dependency, a cycle, an
 * essential module that ships off, or a missing label.
 */
class FeatureIdTest {

    @Test
    fun noFeatureDependsOnItself() {
        FeatureId.entries.forEach { feature ->
            assertFalse(
                "${feature.name} depends on itself",
                feature.dependsOn.contains(feature),
            )
        }
    }

    @Test
    fun dependencyGraphHasNoCycles() {
        val visiting = mutableSetOf<FeatureId>()
        val done = mutableSetOf<FeatureId>()

        fun visit(feature: FeatureId) {
            if (feature in done) return
            assertFalse("dependency cycle through ${feature.name}", feature in visiting)
            visiting.add(feature)
            feature.dependsOn.forEach { visit(it) }
            visiting.remove(feature)
            done.add(feature)
        }

        FeatureId.entries.forEach { visit(it) }
    }

    @Test
    fun essentialFeaturesAreOnByDefault() {
        FeatureId.entries.filter { it.essential }.forEach { feature ->
            assertTrue(
                "${feature.name} is essential but defaults to off",
                feature.defaultEnabled,
            )
        }
    }

    @Test
    fun everyFeatureIsLabelled() {
        FeatureId.entries.forEach { feature ->
            assertTrue("${feature.name} has no title", feature.title.isNotBlank())
            assertTrue("${feature.name} has no description", feature.description.isNotBlank())
        }
    }

    @Test
    fun featureNamesAreUnique() {
        val names = FeatureId.entries.map { it.name }
        assertTrue("duplicate feature ids", names.size == names.toSet().size)
    }
}
