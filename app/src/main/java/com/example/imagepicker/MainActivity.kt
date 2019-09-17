package com.example.imagepicker

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.squareup.picasso.MemoryPolicy
import com.squareup.picasso.Picasso
import io.reactivex.Completable
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import kotlinx.android.synthetic.main.activity_main.*
import timber.log.Timber
import java.io.File

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Timber.plant(Timber.DebugTree())

        imagesGallery.setOnClickListener {
            pick()
        }

        imagesCamera.setOnClickListener {
            capture()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            REQUEST_CODE_PICK -> if (resultCode == Activity.RESULT_OK) onImagePicked(data!!.data!!)
            REQUEST_CODE_CAMERA -> if (resultCode == Activity.RESULT_OK) onImageCaptured()
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun pick() {
        val pickIntent = Intent(Intent.ACTION_GET_CONTENT)
            .setType("image/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/jpeg", "image/png", "image/gif"))

        startActivityForResult(pickIntent, REQUEST_CODE_PICK)
    }

    /**
     * https://commonsware.com/blog/2016/03/15/how-consume-content-uri.html
     */
    private fun onImagePicked(uri: Uri) {
        saveToFile(uri)
            .subscribeOn(Schedulers.io())
            .flatMap { resizeIfNecessary(it) }
            .doOnSuccess { imageFile ->
                Timber.i("File name: ${imageFile.name}")
                Timber.i("File size: ${imageFile.length()}")
            }
            .doOnError { throwable -> Timber.e(throwable) }
            .flatMapCompletable { imageFile -> Completable.fromAction { imageFile.delete() } }
            .onErrorComplete()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe()
    }

    /**
     * https://developer.android.com/training/camera/photobasics#TaskPath
     */
    private fun capture() {
        // Easiest approach is NOT to make the picture available to all apps (requires no permissions)
        // Instead, save into a file inside our app storage dir
        val tempFile = getFileForCamera()

        Intent(MediaStore.ACTION_IMAGE_CAPTURE).also { takePictureIntent ->
            takePictureIntent.resolveActivity(packageManager)?.let {
                val imageUri = FileProvider.getUriForFile(
                    this,
                    "com.example.imagepicker.fileprovider",
                    tempFile
                )

                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, imageUri)
                startActivityForResult(takePictureIntent, REQUEST_CODE_CAMERA)
            }
        }
    }

    private fun onImageCaptured() {
        Single.fromCallable { getFileForCamera() }
            .subscribeOn(Schedulers.io())
            .flatMap { resizeIfNecessary(it) }
            .doOnSuccess { imageFile ->
                Timber.i("File name: ${imageFile.name}")
                Timber.i("File size: ${imageFile.length()}")
            }
            .doOnError { throwable -> Timber.e(throwable) }
            .flatMapCompletable { imageFile -> Completable.fromAction { imageFile.delete() } }
            .onErrorComplete()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe()
    }

    private fun saveToFile(uri: Uri): Single<File> {
        return Single.fromCallable {
            // Create a temp file in our cache dir so that we can work with the data
            val mimeType = contentResolver.getType(uri)!!
            val extension = mimeType.removeRange(0, mimeType.indexOf('/') + 1)
            val tempFile = getFileForUri(extension)

            val inputStream = contentResolver.openInputStream(uri)!!
            val outputStream = tempFile.outputStream()

            inputStream.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }

            tempFile
        }
    }

    private fun getFileForUri(extension: String): File {
        return File(cacheDir, "photoForUri.$extension")
    }

    private fun getFileForCamera(): File {
        return File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "photoFromCamera.jpg")
    }

    private fun resizeIfNecessary(tempFile: File): Single<File> {
        return Single.fromCallable {
            // Resize if needed
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tempFile.absolutePath, options)
            val (width, height) = options.outWidth to options.outHeight
            // Or....
            val fileSizeBytes = tempFile.length()

            if (fileSizeBytes > TWENTY_MB_IN_BYTES) {
                Timber.i("File is $fileSizeBytes - resizing...")
                // Resize
                val preserveAspectRatio = true
                val onlyScaleDown = true

                val resizedBitmap = Picasso.get()
                        .load(tempFile)
                        .resize(RESIZE_DIMEN, RESIZE_DIMEN)
                        .apply { if (preserveAspectRatio) centerInside() }
                        .apply { if (onlyScaleDown) onlyScaleDown() }
                        .memoryPolicy(MemoryPolicy.NO_CACHE, MemoryPolicy.NO_STORE)
                        .get()

                val resizedExtension = when (tempFile.extension.toLowerCase()) {
                    "jpeg", "jpg" -> "jpeg"
                    else -> "png"
                }

                val resizedFileName = "${tempFile.nameWithoutExtension}-resized.$resizedExtension"
                val resizedFile = File(cacheDir, resizedFileName).also {
                    it.outputStream().use { resizedOutput ->
                        val format = if (resizedExtension == "jpeg") Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
                        resizedBitmap.compress(format, 99, resizedOutput)
                    }
                }
                tempFile.delete()

                resizedFile
            } else {
                tempFile
            }
        }
    }

    companion object {
        private const val REQUEST_CODE_PICK = 3538
        private const val REQUEST_CODE_CAMERA = 9238

        private const val TWENTY_MB_IN_BYTES = 20L * 1048576L

        /**
         * If an image is too large, we'll try to resize its largest dimention to this (arbitrary) value
         *
         * JPEG: Max 8.25 bits per pixel * 1 million pixels = 1.03125 megabytes at most
         * PNG: Max 16 bits per pixel * 1 million pixels = 2 megabytes at most
         */
        const val RESIZE_DIMEN = 1000
    }
}
