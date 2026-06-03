import argparse
import os
import uvicorn
from dotenv import load_dotenv

load_dotenv()

if __name__ == "__main__":

    parser = argparse.ArgumentParser(description="Run FastAPI server in different modes.")
    parser.add_argument("--prod", action="store_true", help="Run the server in production mode.")
    parser.add_argument("--dev", action="store_true", help="Run the server in development mode.")

    args = parser.parse_args()

    if args.prod:
        os.environ["ENV"] = "prod"
    else:
        os.environ["ENV"] = "dev"

    uvicorn.run("app.main:app", host=os.getenv("HOST", "0.0.0.0"), port=int(os.getenv("PORT", "8008")))
