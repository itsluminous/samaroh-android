package com.itsluminous.samaroh.feature.files

import androidx.core.content.FileProvider

/**
 * Feature-local [FileProvider] subclass: manifest `<provider>` nodes are keyed by class
 * name at manifest merge, so two features sharing `androidx.core.content.FileProvider`
 * would collide — each declares its own subclass and authority (the expenses pattern).
 */
class FilesFileProvider : FileProvider()
