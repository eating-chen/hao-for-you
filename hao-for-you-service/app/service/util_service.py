"""define util function"""
from app.core.config import config



def add_operation(a: float, b: float) -> float:
    """
    Add two numbers.
    a: First number
    b: Second number
    return: Sum of a and b
    """
    return a + b

def subtract_operation(a: float, b: float) -> float:
    """
    Subtract two numbers.
    a: First number
    b: Second number
    return: Difference of a and b
    """
    return a - b

def multiply_operation(a: float, b: float) -> float:
    """
    Multiply two numbers.
    a: First number
    b: Second number
    return: Product of a and b
    """
    return a * b

def divide_operation(a: float, b: float) -> float:
    """
    Divide two numbers.
    a: First number
    b: Second number
    return: Quotient of a and b
    """
    if b == 0:
        raise ValueError("Cannot divide by zero.")
    return a / b