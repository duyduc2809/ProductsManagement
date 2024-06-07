package com.example.productsmanagement

import android.app.Activity
import android.content.Intent
import android.content.Intent.ACTION_GET_CONTENT
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toFile
import androidx.lifecycle.lifecycleScope
import com.example.productsmanagement.databinding.ActivityMainBinding
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage
import com.skydoves.colorpickerview.ColorEnvelope
import com.skydoves.colorpickerview.ColorPickerDialog
import com.skydoves.colorpickerview.listeners.ColorEnvelopeListener
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

//Change the security access settings in the firebase cloud and firestore
class MainActivity : AppCompatActivity() {
    private val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    val selectedColors = mutableListOf<Int>()
    private var selectedImages = mutableListOf<Uri>()
    private val firestore = Firebase.firestore
    private val storage = Firebase.storage.reference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        binding.buttonColorPicker.setOnClickListener {
            ColorPickerDialog
                .Builder(this)
                .setTitle("Product color")
                .setPositiveButton("Select", object : ColorEnvelopeListener {

                    override fun onColorSelected(envelope: ColorEnvelope?, fromUser: Boolean) {
                        envelope?.let {
                            selectedColors.add(it.color)
                            updateColors()
                        }
                    }

                }).setNegativeButton("Cancel") { colorPicker, _ ->
                    colorPicker.dismiss()
                }.show()
        }

        val selectImagesActivityResult =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    val intent = result.data

                    //Multiple images selected
                    if (intent?.clipData != null) {
                        val count = intent.clipData?.itemCount ?: 0
                        (0 until count).forEach {
                            val imagesUri = intent.clipData?.getItemAt(it)?.uri
                            imagesUri?.let { selectedImages.add(it) }
                        }

                        //One images was selected
                    } else {
                        val imageUri = intent?.data
                        imageUri?.let { selectedImages.add(it) }
                    }
                    updateImages()
                }
            }

        binding.buttonImagesPicker.setOnClickListener {
            val intent = Intent(ACTION_GET_CONTENT)
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            intent.type = "image/*"
            selectImagesActivityResult.launch(intent)
        }


    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.toolbar_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.saveProduct) {
            val productValidation = validateInformation()
            if (!productValidation) {
                Toast.makeText(this, "Check your inputs", Toast.LENGTH_SHORT).show()
                return false
            }
            saveProducts() {
                Log.d("test", it.toString())
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun validateInformation(): Boolean {
        if (selectedImages.isEmpty())
            return false
        if (binding.edName.text.toString().trim().isEmpty())
            return false
        if (binding.edCategory.text.toString().trim().isEmpty())
            return false
        if (binding.edPrice.text.toString().trim().isEmpty())
            return false
        return true
    }

    private fun saveProducts(state: (Boolean) -> Unit) {
        val sizes = getSizesList(binding.edSizes.text.toString().trim())
        val name = binding.edName.text.toString().trim()
        val category = binding.edCategory.text.toString().trim()
        val productDescription = binding.edDescription.text.toString().trim()
        val price = binding.edPrice.text.toString().trim().toFloat()
        val offerPercentage = binding.edOfferPercentage.text.toString().trim().toFloatOrNull()

        lifecycleScope.launch {
            showLoading()
            try {
                val imagesByteArrays = async {
                    val images = mutableListOf<ByteArray>()
                    selectedImages.forEach { imageUri ->
                        val stream = ByteArrayOutputStream()
                        val imageBmp = contentResolver.openInputStream(imageUri)?.use {
                            BitmapFactory.decodeStream(it)
                        }
                        if (imageBmp?.compress(Bitmap.CompressFormat.JPEG, 85, stream) == true) {
                            images.add(stream.toByteArray())
                        } else {
                            Log.e("ImageCompression", "Error compressing image: $imageUri")
                        }
                    }
                    images
                }.await()

                // Upload images to Firebase Storage
                val imagesStorageRef = storage.child("products/images")
                val imagesDownloadUrls = mutableListOf<String>()
                imagesByteArrays.forEach { imageByteArray ->
                    val imageRef = imagesStorageRef.child(UUID.randomUUID().toString())

                    try {
                        val uploadTask = imageRef.putBytes(imageByteArray)
                        val taskResult = uploadTask.await()
                        imagesDownloadUrls.add(taskResult.storage.downloadUrl.await().toString())
                    } catch (e: Exception) {
                        Log.e("FirebaseStorage", "Error uploading image: $e")
                        // Xử lý lỗi ở đây
                    }
                }


                val product = Product(
                    UUID.randomUUID().toString(),
                    name,
                    category,
                    price,
                    offerPercentage,
                    productDescription,
                    selectedColors,
                    sizes,
                    imagesDownloadUrls
                )

                firestore.collection("Products").add(product).addOnSuccessListener {
                    state(true)
                    hideLoading()
                    Toast.makeText(
                        this@MainActivity,
                        "Product saved successfully",
                        Toast.LENGTH_SHORT
                    ).show()
                }.addOnFailureListener {
                    Log.e("Firestore", "Error adding product: $it")
                    state(false)
                    hideLoading()
                    Toast.makeText(this@MainActivity, "Failed to save product", Toast.LENGTH_SHORT)
                        .show()
                }
            } catch (e: Exception) {
                Log.e("ProductSaving", "General error: $e")
                state(false)
                hideLoading()
                Toast.makeText(
                    this@MainActivity,
                    "An unexpected error occurred",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun hideLoading() {
        binding.progressbar.visibility = View.INVISIBLE
    }

    private fun showLoading() {
        binding.progressbar.visibility = View.VISIBLE

    }

    private fun getSizesList(sizes: String): List<String>? {
        if (sizes.isEmpty())
            return null
        val sizesList = sizes.split(",").map { it.trim() }
        return sizesList
    }

    private fun updateColors() {
        var colors = ""
        selectedColors.forEach {
            colors = "$colors ${Integer.toHexString(it)}, "
        }
        binding.tvSelectedColors.text = colors
    }

    private fun updateImages() {
        binding.tvSelectedImages.setText(selectedImages.size.toString())
    }


}