import "./loadEnvironment.mjs";
import express from "express";
import cors from "cors";
import "express-async-errors";
import posts from "./routes/posts.mjs";
import createTables from "./createTables.mjs";

const PORT = process.env.PORT || 5050;
const app = express();

app.use(cors());
app.use(express.json());

app.use("/database", posts);

app.use((err, _req, res, next) => {
  res.status(500).send("Uh oh! An unexpected error occurred.");
});

async function startServer() {
  try {
    await createTables();  // Ensure tables exist on startup

    app.listen(PORT, "0.0.0.0", () => {
      console.log(`Server is running on port: ${PORT}`);
    });
  } catch (error) {
    console.error("Failed to start server:", error);
    process.exit(1);
  }
}

startServer();