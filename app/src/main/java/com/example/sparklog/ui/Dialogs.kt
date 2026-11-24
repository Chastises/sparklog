package com.example.sparklog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.sparklog.data.Customer
import com.example.sparklog.data.Material
import com.example.sparklog.data.Trip
import com.example.sparklog.data.WorkEntry
import java.time.LocalDate

internal fun String.toNum(): Double? = trim().replace(',', '.').toDoubleOrNull()
internal fun fmt(d: Double): String = if (d % 1.0 == 0.0) d.toInt().toString() else "%.1f".format(d)
private fun validDate(s: String) = runCatching { LocalDate.parse(s.trim()) }.isSuccess

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    number: Boolean = false,
    singleLine: Boolean = true,
    isError: Boolean = false,
    keyboard: KeyboardType? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        isError = isError,
        keyboardOptions = when {
            keyboard != null -> KeyboardOptions(keyboardType = keyboard)
            number -> KeyboardOptions(keyboardType = KeyboardType.Decimal)
            else -> KeyboardOptions.Default
        },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun FormDialog(
    title: String,
    canSave: Boolean,
    onSave: () -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { content() } },
        confirmButton = { TextButton(onClick = onSave, enabled = canSave) { Text("Save") } },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/**
 * Dropdown of customers. Finished customers are hidden unless one is already selected.
 * "+ New customer" opens the customer form and selects the result.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomerPicker(vm: MainVm, selectedId: String, onSelect: (String) -> Unit) {
    val customers by vm.customers.collectAsState(emptyList())
    var expanded by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Customer?>(null) }

    val selected = customers.find { it.id == selectedId } ?: pending?.takeIf { it.id == selectedId }
    val options = customers.filter { !it.finished || it.id == selectedId }
    val shown = when {
        selected != null -> selected.name
        selectedId.isNotBlank() -> "Unknown customer"
        else -> "No customer"
    }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = shown,
            onValueChange = {},
            readOnly = true,
            label = { Text("Customer") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth().padding(vertical = 4.dp),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("No customer") }, onClick = { onSelect(""); expanded = false })
            options.forEach { c ->
                DropdownMenuItem(
                    text = { Text(c.name + if (c.finished) " (finished)" else "") },
                    onClick = { onSelect(c.id); expanded = false },
                )
            }
            DropdownMenuItem(text = { Text("+ New customer") }, onClick = { expanded = false; creating = true })
        }
    }

    if (creating) {
        CustomerDialog(
            initial = null,
            onSave = { c -> vm.save(c); pending = c; onSelect(c.id); creating = false },
            onDelete = null,
            onDismiss = { creating = false },
        )
    }
}

@Composable
fun CustomerDialog(
    initial: Customer?,
    onSave: (Customer) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var contact by remember { mutableStateOf(initial?.contactPerson ?: "") }
    var phone by remember { mutableStateOf(initial?.phone ?: "") }
    var email by remember { mutableStateOf(initial?.email ?: "") }
    var address by remember { mutableStateOf(initial?.address ?: "") }
    var postal by remember { mutableStateOf(initial?.postalCode ?: "") }
    var city by remember { mutableStateOf(initial?.city ?: "") }
    var orgNumber by remember { mutableStateOf(initial?.orgNumber ?: "") }
    var propertyId by remember { mutableStateOf(initial?.propertyId ?: "") }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }
    var finished by remember { mutableStateOf(initial?.finished ?: false) }

    FormDialog(
        title = if (initial == null) "New customer" else "Edit customer",
        canSave = name.isNotBlank(),
        onSave = {
            val base = initial ?: Customer(name = name.trim())
            onSave(base.copy(
                name = name.trim(), contactPerson = contact.trim(), phone = phone.trim(), email = email.trim(),
                address = address.trim(), postalCode = postal.trim(), city = city.trim(),
                orgNumber = orgNumber.trim(), propertyId = propertyId.trim(), notes = notes.trim(),
                finished = finished,
            ))
        },
        onDelete = onDelete,
        onDismiss = onDismiss,
    ) {
        Field("Name / company", name, { name = it })
        Field("Contact person", contact, { contact = it })
        Field("Phone", phone, { phone = it }, keyboard = KeyboardType.Phone)
        Field("Email", email, { email = it }, keyboard = KeyboardType.Email)
        Field("Street address", address, { address = it })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) { Field("Postal code", postal, { postal = it }) }
            Column(Modifier.weight(2f)) { Field("City", city, { city = it }) }
        }
        Field("Org / ID number", orgNumber, { orgNumber = it })
        Field("Property designation", propertyId, { propertyId = it })
        Field("Notes", notes, { notes = it }, singleLine = false)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Finished")
                Text("Hidden from the customer dropdown", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            }
            Switch(checked = finished, onCheckedChange = { finished = it })
        }
    }
}

@Composable
fun WorkDialog(
    vm: MainVm,
    initial: WorkEntry?,
    date: String,
    onSave: (WorkEntry) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var customerId by remember { mutableStateOf(initial?.customerId ?: "") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var hours by remember { mutableStateOf(initial?.let { fmt(it.hours) } ?: "") }
    FormDialog(
        title = if (initial == null) "Work done, $date" else "Edit work, $date",
        canSave = hours.toNum() != null,
        onSave = {
            val base = initial ?: WorkEntry(date = date)
            onSave(base.copy(customerId = customerId, description = description.trim(), hours = hours.toNum() ?: 0.0))
        },
        onDelete = onDelete,
        onDismiss = onDismiss,
    ) {
        CustomerPicker(vm, customerId) { customerId = it }
        Field("Hours", hours, { hours = it }, number = true, isError = hours.isNotBlank() && hours.toNum() == null)
        Field("What was done", description, { description = it }, singleLine = false)
    }
}

@Composable
fun TripDialog(
    vm: MainVm,
    initial: Trip?,
    defaultDate: String,
    onSave: (Trip) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var date by remember { mutableStateOf(initial?.date ?: defaultDate) }
    var customerId by remember { mutableStateOf(initial?.customerId ?: "") }
    var from by remember { mutableStateOf(initial?.fromPlace ?: "") }
    var to by remember { mutableStateOf(initial?.toPlace ?: "") }
    var km by remember { mutableStateOf(initial?.let { fmt(it.km) } ?: "") }
    var purpose by remember { mutableStateOf(initial?.purpose ?: "") }
    FormDialog(
        title = if (initial == null) "Add trip" else "Edit trip",
        canSave = validDate(date) && km.toNum() != null,
        onSave = {
            val base = initial ?: Trip(date = date.trim())
            onSave(base.copy(date = date.trim(), customerId = customerId, fromPlace = from.trim(),
                toPlace = to.trim(), km = km.toNum() ?: 0.0, purpose = purpose.trim()))
        },
        onDelete = onDelete,
        onDismiss = onDismiss,
    ) {
        Field("Date (yyyy-MM-dd)", date, { date = it }, isError = !validDate(date))
        CustomerPicker(vm, customerId) { customerId = it }
        Field("From", from, { from = it })
        Field("To", to, { to = it })
        Field("Distance (km)", km, { km = it }, number = true, isError = km.isNotBlank() && km.toNum() == null)
        Field("Purpose / note", purpose, { purpose = it })
    }
}

@Composable
fun MaterialDialog(
    vm: MainVm,
    initial: Material?,
    defaultDate: String,
    onSave: (Material) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var date by remember { mutableStateOf(initial?.date ?: defaultDate) }
    var customerId by remember { mutableStateOf(initial?.customerId ?: "") }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var qty by remember { mutableStateOf(initial?.let { fmt(it.quantity) } ?: "1") }
    var unit by remember { mutableStateOf(initial?.unit ?: "pcs") }
    FormDialog(
        title = if (initial == null) "Add material" else "Edit material",
        canSave = validDate(date) && name.isNotBlank() && qty.toNum() != null,
        onSave = {
            val base = initial ?: Material(date = date.trim(), name = name.trim())
            onSave(base.copy(date = date.trim(), customerId = customerId, name = name.trim(),
                quantity = qty.toNum() ?: 1.0, unit = unit.trim()))
        },
        onDelete = onDelete,
        onDismiss = onDismiss,
    ) {
        Field("Date (yyyy-MM-dd)", date, { date = it }, isError = !validDate(date))
        CustomerPicker(vm, customerId) { customerId = it }
        Field("Material", name, { name = it })
        Field("Quantity", qty, { qty = it }, number = true, isError = qty.isNotBlank() && qty.toNum() == null)
        Field("Unit (pcs, m, box...)", unit, { unit = it })
    }
}
