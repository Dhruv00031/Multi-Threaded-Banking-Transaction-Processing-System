package com.banking.repository;

import com.banking.model.Customer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class CustomerRepository {

    private static final RowMapper<Customer> CUSTOMER_ROW_MAPPER = (resultSet, rowNumber) ->
            new Customer(
                    resultSet.getString("customer_id"),
                    resultSet.getString("full_name"),
                    resultSet.getString("email"),
                    resultSet.getString("phone"),
                    resultSet.getTimestamp("created_at").toLocalDateTime());

    private final JdbcTemplate jdbcTemplate;

    public CustomerRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Customer> findById(String customerId) {
        return jdbcTemplate.query(
                        "SELECT customer_id, full_name, email, phone, created_at "
                                + "FROM customers WHERE customer_id = ?",
                        CUSTOMER_ROW_MAPPER,
                        customerId)
                .stream()
                .findFirst();
    }
}
