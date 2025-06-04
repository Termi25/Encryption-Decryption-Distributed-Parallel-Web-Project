import express from "express";
import pool from "../db/conn.mjs";
import multer from "multer";
import crypto from "crypto";

const router = express.Router();
const storage = multer.memoryStorage();
const upload = multer({ storage });

// Helper to convert MySQL row to your "post" object structure
function mapRowToPost(row) {
  return {
    id: row.id,
    requestIv: row.requestIv,
    mode: row.mode,
    aesLength: row.aesLength,
    requestPassword: row.requestPassword,
    fileName: row.fileName,
    operation: row.operation,
    date: row.date,
    processedAt: row.processedAt,
    processedFileName: row.processedFileName,
  };
}

// Get list of 50 posts
router.get("/", async (req, res) => {
  try {
    const [rows] = await pool.query("SELECT * FROM data ORDER BY date DESC LIMIT 50");
    const posts = rows.map(mapRowToPost);
    res.status(200).json(posts);
  } catch (error) {
    console.error("Error fetching posts:", error);
    res.status(500).json({ error: "Internal server error" });
  }
});

// Get posts by an array of IDs (expects array of objects with .code = id)
router.post("/receiveInputedData", async (req, res) => {
  try {
    const inputArray = req.body;
    if (!Array.isArray(inputArray)) {
      return res.status(400).json({ error: "Expected an array of objects." });
    }

    const ids = inputArray
      .map(item => parseInt(item.code))
      .filter(id => !isNaN(id));

    if (ids.length === 0) {
      return res.status(400).json({ error: "No valid IDs provided." });
    }

    const [rows] = await pool.query(
      `SELECT * FROM data WHERE id IN (${ids.map(() => "?").join(",")})`,
      ids
    );

    const posts = rows.map(mapRowToPost);
    res.status(200).json(posts);
  } catch (error) {
    console.error("Error retrieving posts:", error);
    res.status(500).json({ error: "Internal server error" });
  }
});

// Get single post by ID
router.get("/:id", async (req, res) => {
  try {
    const id = parseInt(req.params.id);
    if (isNaN(id)) return res.status(400).send("Invalid ID");

    const [rows] = await pool.query("SELECT * FROM data WHERE id = ?", [id]);
    if (rows.length === 0) return res.status(404).send("Not found");

    res.status(200).json(mapRowToPost(rows[0]));
  } catch (error) {
    console.error("Error fetching post:", error);
    res.status(500).send("Internal server error");
  }
});

// Add a new document with auto-generated requestIv
router.post("/receive-form", async (req, res) => {
  try {
    const { mode, aesLength, requestPassword, fileName, operation } = req.body;

    const requestIv = crypto.randomBytes(16).toString("hex");

    const [result] = await pool.query(
      `INSERT INTO data (requestIv, mode, aesLength, requestPassword, fileName, operation, date)
       VALUES (?, ?, ?, ?, ?, ?, NOW())`,
      [requestIv, mode, aesLength, requestPassword, fileName, operation]
    );

    res.status(200).json({ acknowledged: true, insertedId: result.insertId, requestIv });
  } catch (error) {
    console.error("Error inserting document:", error);
    res.status(500).json({ error: "Internal server error" });
  }
});

// Delete post and associated file (1-to-1)
router.delete("/:id", async (req, res) => {
  try {
    const id = parseInt(req.params.id);
    if (isNaN(id)) return res.status(400).json({ error: "Invalid ID format" });

    // First get the processedFile if exists
    const [rows] = await pool.query("SELECT processedFileId FROM data WHERE id = ?", [id]);
    if (rows.length === 0) return res.status(404).json({ error: "Document not found" });

    const processedFileId = rows[0].processedFileId;

    // Delete file if exists
    if (processedFileId) {
      await pool.query("DELETE FROM processedFiles WHERE id = ?", [processedFileId]);
    }

    // Delete main data
    const [result] = await pool.query("DELETE FROM data WHERE id = ?", [id]);
    res.status(200).json({ deletedCount: result.affectedRows });
  } catch (error) {
    console.error("Error deleting document:", error);
    res.status(500).json({ error: "Internal server error" });
  }
});

// Upload processed file and update data table with file info
router.post("/uploadProcessedFile", upload.single("file"), async (req, res) => {
  try {
    const { id } = req.body;
    const file = req.file;

    const docId = parseInt(id);
    if (!docId || !file) {
      return res.status(400).json({ error: "Invalid id or missing file" });
    }

    // Insert file into processedFiles table
    const [fileResult] = await pool.query(
      "INSERT INTO processedFiles (data_id, filename, filedata, contentType, uploadDate) VALUES (?, ?, ?, ?, NOW())",
      [docId, file.originalname, file.buffer, file.mimetype]
    );

    // Update main data record
    await pool.query(
      "UPDATE data SET processedFileId = ?, processedFileName = ?, processedAt = NOW() WHERE id = ?",
      [fileResult.insertId, file.originalname, docId]
    );

    res.status(200).json({ message: "File uploaded and document updated", fileId: fileResult.insertId });
  } catch (error) {
    console.error("Error uploading processed file:", error);
    res.status(500).json({ error: "Internal server error" });
  }
});

// Download processed file by docId
router.get("/download/final/:docId", async (req, res) => {
  try {
    const docId = parseInt(req.params.docId);
    if (isNaN(docId)) return res.status(400).send("Invalid document ID");

    const [dataRows] = await pool.query("SELECT processedFileId FROM data WHERE id = ?", [docId]);
    if (dataRows.length === 0 || !dataRows[0].processedFileId) {
      return res.status(404).send("No associated file found");
    }

    const fileId = dataRows[0].processedFileId;

    const [fileRows] = await pool.query("SELECT filename, filedata, contentType FROM processedFiles WHERE id = ?", [fileId]);
    if (fileRows.length === 0) return res.status(404).send("File not found");

    const file = fileRows[0];

    res.set({
      "Content-Type": file.contentType || "application/octet-stream",
      "Content-Disposition": `attachment; filename="${file.filename}"`,
    });

    res.send(file.filedata);
  } catch (error) {
    console.error("Error downloading file:", error);
    res.status(500).send("Internal server error");
  }
});

// Patch to update requestIv (optional, but now auto-generated on insert)
router.patch("/updateIv/:id", async (req, res) => {
  try {
    const id = parseInt(req.params.id);
    const { requestIv } = req.body;

    if (!requestIv) return res.status(400).json({ error: "'requestIv' field is required" });
    if (isNaN(id)) return res.status(400).json({ error: "Invalid 'id' format" });

    const [result] = await pool.query("UPDATE data SET requestIv = ? WHERE id = ?", [requestIv, id]);
    if (result.affectedRows === 0) return res.status(404).json({ error: "Document not found" });

    res.status(200).json({ message: "requestIv updated successfully" });
  } catch (error) {
    console.error("Error updating requestIv:", error);
    res.status(500).json({ error: "Internal server error" });
  }
});

// Check password for a document
router.post("/check-password/:id", async (req, res) => {
  try {
    const id = parseInt(req.params.id);
    const { password } = req.body;

    if (!password) return res.status(400).json({ error: "Password is required" });
    if (isNaN(id)) return res.status(400).json({ error: "Invalid ID format" });

    const [rows] = await pool.query("SELECT requestPassword FROM data WHERE id = ?", [id]);
    if (rows.length === 0) return res.status(404).json({ error: "Document not found" });

    if (rows[0].requestPassword === password) {
      res.status(200).json({ success: true, message: "Password is correct." });
    } else {
      res.status(401).json({ success: false, message: "Incorrect password." });
    }
  } catch (error) {
    console.error("Error checking password:", error);
    res.status(500).json({ error: "Internal server error" });
  }
});

export default router;
