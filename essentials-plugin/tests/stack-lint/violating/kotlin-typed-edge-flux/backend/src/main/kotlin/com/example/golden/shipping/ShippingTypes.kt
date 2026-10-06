package com.example.golden.shipping

import dk.trustworks.essentials.kotlin.types.StringValueType
import dk.trustworks.essentials.types.CharSequenceType

// Only SingleValueTypeConverter can build this one: its sole constructor takes a CharSequence.
class LegacyRef(value: CharSequence) : CharSequenceType<LegacyRef>(value)

// Spring binds this one through its String primary constructor, without the Essentials converter.
class Ticket(value: String) : CharSequenceType<Ticket>(value)

// Trap: a value class is unboxed to String in the JVM signature and needs nothing.
@JvmInline
value class ShipmentId(override val value: String) : StringValueType<ShipmentId>
