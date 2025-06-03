import { MongoClient } from "mongodb";

const connectionString = process.env.MONGO_URI || "mongodb://localhost:27017";
const client = new MongoClient(connectionString);

let conn;

try {
  conn = await client.connect();
} catch(e) {
  console.error("Failed to connect to MongoDB:", e);
}

let db = conn.db("ism-dad");
export default db;
