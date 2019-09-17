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
            camera()
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
        // Create a temp file in our cache dir so that we can work with the data
        val mimeType = contentResolver.getType(uri)!!
        val extension = mimeType.removeRange(0, mimeType.indexOf('/') + 1)

        val tempFileName = "photoFromGallery.$extension"
        val tempFile = File(cacheDir, tempFileName)

        val inputStream = contentResolver.openInputStream(uri)!!
        val outputStream = tempFile.outputStream()

        inputStream.use { input ->
            outputStream.use { output ->
                input.copyTo(output)
            }
        }

        doStuff(tempFile)
    }

    /**
     * https://developer.android.com/training/camera/photobasics#TaskPath
     */
    private fun camera() {
        // Easiest approach is not to make the picture available to all apps (requires no permissions)
        val tempFileName = "photoFromCamera.jpg"
        val tempFile = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), tempFileName)

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
        // We know the file we created
        val tempFileName = "photoFromCamera.jpg"
        val tempFile = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), tempFileName)

        doStuff(tempFile)
    }

    private fun doStuff(tempFile: File) {
        var resizedFile: File? = null

        /*
        Resize if needed
         */
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(tempFile.absolutePath, options)
        val (width, height) = options.outWidth to options.outHeight
        // Or....
        val fileSizeBytes = tempFile.length()

        val imageFileToUse = if (fileSizeBytes > TWENTY_MB_IN_BYTES) {
            Timber.i("File is $fileSizeBytes - resizing...")
            // Resize
            val preserveAspectRatio = true
            val onlyScaleDown = true

            Single.fromCallable {
                Picasso.get()
                    .load(tempFile)
                    .resize(RESIZE_DIMEN, RESIZE_DIMEN)
                    .apply { if (preserveAspectRatio) centerInside() }
                    .apply { if (onlyScaleDown) onlyScaleDown() }
                    .memoryPolicy(MemoryPolicy.NO_CACHE, MemoryPolicy.NO_STORE)
                    .get()
            }.flatMap { resizedBitmap ->
                val resizedExtension = when (tempFile.extension.toLowerCase()) {
                    "jpeg", "jpg" -> "jpeg"
                    else -> "png"
                }

                val resizedFileName = "${tempFile.nameWithoutExtension}-resized.$resizedExtension"
                resizedFile = File(cacheDir, resizedFileName).also {
                    it.outputStream().use { resizedOutput ->
                        val format = if (resizedExtension == "jpeg") Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
                        resizedBitmap.compress(format, 99, resizedOutput)
                    }
                }

                Single.just(resizedFile!!)
            }
        } else {
            Single.just(tempFile)
        }

        imageFileToUse.subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .doAfterTerminate { cleanUp(tempFile, resizedFile) }
            .subscribe({ imageFile ->
                // Do stuff with the file
                Timber.i("File name: ${imageFile.name}")
                Timber.i("File size: ${imageFile.length()}")
            }, { throwable ->
                Timber.e(throwable, "Error")
            })

    }

    private fun cleanUp(vararg files: File?) {
        files.forEach { file -> file?.let { runCatching { it.delete() } } }
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
