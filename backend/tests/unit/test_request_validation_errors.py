from __future__ import annotations

from app.core.errors import request_validation_details


def test_validation_error_names_field_and_limit_without_echoing_input() -> None:
    secret_input = "x" * 101

    message, fields = request_validation_details(
        [
            {
                "type": "string_too_long",
                "loc": ("body", "device_id"),
                "msg": "String should have at most 100 characters",
                "input": secret_input,
                "ctx": {"max_length": 100},
            }
        ]
    )

    assert message == "device id must be at most 100 characters."
    assert fields == [
        {
            "field": "device_id",
            "type": "string_too_long",
            "message": "device id must be at most 100 characters.",
        }
    ]
    assert secret_input not in message
    assert secret_input not in repr(fields)


def test_nested_validation_error_uses_actionable_indexed_field_path() -> None:
    message, fields = request_validation_details(
        [
            {
                "type": "greater_than_equal",
                "loc": ("body", "lines", 2, "qty"),
                "msg": "Input should be greater than or equal to 1",
                "ctx": {"ge": 1},
            }
        ]
    )

    assert message == "lines[2].qty must be at least 1."
    assert fields[0]["field"] == "lines[2].qty"
