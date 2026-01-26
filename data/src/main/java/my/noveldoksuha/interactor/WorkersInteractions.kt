package my.noveldokusha.interactor

import my.noveldokusha.core.domain.LibraryCategory

interface WorkersInteractions {
    fun checkForLibraryUpdates(libraryCategory: LibraryCategory)
}