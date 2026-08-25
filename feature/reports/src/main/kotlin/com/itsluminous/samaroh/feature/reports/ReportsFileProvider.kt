package com.itsluminous.samaroh.feature.reports

import androidx.core.content.FileProvider

/**
 * Feature-local [FileProvider] subclass: manifest `<provider>` nodes are keyed by class
 * name at manifest merge, so two features sharing `androidx.core.content.FileProvider`
 * collide in `:app`. A distinct subclass per feature keeps the providers independent.
 */
class ReportsFileProvider : FileProvider()
