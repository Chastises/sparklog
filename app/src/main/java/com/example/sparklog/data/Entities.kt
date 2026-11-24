package com.example.sparklog.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

// Dates are stored as ISO strings (yyyy-MM-dd). Every row has a UUID id, an updatedAt
// timestamp (epoch millis) and a soft-delete flag so rows can be synced (last write wins).
// customerId is "" when an entry is not tied to a customer.

@Entity(tableName = "customers")
data class Customer(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val contactPerson: String = "",
    val phone: String = "",
    val email: String = "",
    val address: String = "",
    val postalCode: String = "",
    val city: String = "",
    val orgNumber: String = "",   // company org number or personal ID number, needed on invoices / ROT
    val propertyId: String = "",  // property designation (fastighetsbeteckning), needed for ROT
    val notes: String = "",
    val finished: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
)

@Entity(tableName = "work_entries")
data class WorkEntry(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val date: String,
    val customerId: String = "",
    val description: String = "",
    val hours: Double = 0.0,
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
)

@Entity(tableName = "trips")
data class Trip(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val date: String,
    val customerId: String = "",
    val fromPlace: String = "",
    val toPlace: String = "",
    val km: Double = 0.0,
    val purpose: String = "",
    val source: String = "manual", // "manual" or "gps"
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
)

@Entity(tableName = "materials")
data class Material(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val date: String,
    val customerId: String = "",
    val name: String,
    val quantity: Double = 1.0,
    val unit: String = "pcs",
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
)
