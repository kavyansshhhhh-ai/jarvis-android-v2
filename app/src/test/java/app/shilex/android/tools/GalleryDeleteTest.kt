package app.shilex.android.tools

import app.shilex.android.tools.GalleryPhotoMatcher.MediaPhoto
import app.shilex.android.tools.GalleryPhotoMatcher.Resolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryDeleteTest {

    private val samplePhotos = listOf(
        MediaPhoto(id = 101, displayName = "IMG_20261115_120000.jpg", relativePath = "DCIM/Camera/", dateModifiedSec = 2000L, width = 4000, height = 3000, sizeBytes = 2_400_000L),
        MediaPhoto(id = 102, displayName = "Screenshot_20261115.png", relativePath = "Pictures/Screenshots/", dateModifiedSec = 1500L, width = 1080, height = 2400, sizeBytes = 480_000L),
        MediaPhoto(id = 103, displayName = "family_vacation.jpg", relativePath = "DCIM/Camera/", dateModifiedSec = 1000L, width = 3000, height = 2000, sizeBytes = 1_200_000L),
    )

    @Test
    fun resolvesLatestPhotoByDefaultOrWhenTargetIsLatest() {
        val resDefault = GalleryPhotoMatcher.resolve("", samplePhotos)
        assertTrue(resDefault is Resolution.Single)
        assertEquals(101L, (resDefault as Resolution.Single).photo.id)

        val resLatest = GalleryPhotoMatcher.resolve("latest", samplePhotos)
        assertTrue(resLatest is Resolution.Single)
        assertEquals(101L, (resLatest as Resolution.Single).photo.id)

        val resYeh = GalleryPhotoMatcher.resolve("yeh photo", samplePhotos)
        assertTrue(resYeh is Resolution.Single)
        assertEquals(101L, (resYeh as Resolution.Single).photo.id)
    }

    @Test
    fun resolvesExactPhotoName() {
        val res = GalleryPhotoMatcher.resolve("family_vacation.jpg", samplePhotos)
        assertTrue(res is Resolution.Single)
        assertEquals(103L, (res as Resolution.Single).photo.id)

        val resWithoutExt = GalleryPhotoMatcher.resolve("family_vacation", samplePhotos)
        assertTrue(resWithoutExt is Resolution.Single)
        assertEquals(103L, (resWithoutExt as Resolution.Single).photo.id)
    }

    @Test
    fun generatesSafetyConfirmationPrompt() {
        val photo = MediaPhoto(id = 101, displayName = "IMG_20261115_120000.jpg")
        val prompt = GalleryPhotoMatcher.confirmationPrompt(photo)
        assertEquals("Kya aap isi photo ko delete karna chahte hain? (IMG_20261115_120000.jpg)", prompt)
    }

    @Test
    fun returnsNoneWhenNotFound() {
        val res = GalleryPhotoMatcher.resolve("non_existent_file.png", samplePhotos)
        assertTrue(res is Resolution.None)
    }

    @Test
    fun searchesByAlbumKeywordAndFormatsMetadata() {
        val screenshots = GalleryPhotoMatcher.searchPhotos("screenshot", samplePhotos)
        assertEquals(1, screenshots.size)
        assertEquals(102L, screenshots.single().id)

        val cameraPhotos = GalleryPhotoMatcher.searchPhotos("camera", samplePhotos)
        assertEquals(listOf(101L, 103L), cameraPhotos.map { it.id })

        val summary = GalleryPhotoMatcher.formatMetadataSummary(samplePhotos[0])
        assertTrue(summary.contains("IMG_20261115_120000.jpg"))
        assertTrue(summary.contains("DCIM/Camera"))
        assertTrue(summary.contains("4000x3000"))
    }
}
