package com.lezzat.b2b2c.frozenfood.exception;

/** Permintaan perencanaan rute kurir tidak valid (kosong, duplikat, >8 titik, status/pelaksana/koordinat tidak memenuhi syarat). HTTP 400. */
public class InvalidDeliveryRouteException extends RuntimeException {
    public InvalidDeliveryRouteException(String message) {
        super(message);
    }
}
