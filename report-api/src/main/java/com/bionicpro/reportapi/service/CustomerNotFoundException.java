package com.bionicpro.reportapi.service;

/** Пользователь не найден в CRM (нет e-mail в customers). */
public class CustomerNotFoundException extends RuntimeException {

    private final String email;

    public CustomerNotFoundException(String email) {
        super("Customer not found in CRM: " + email);
        this.email = email;
    }

    public String email() {
        return email;
    }
}
