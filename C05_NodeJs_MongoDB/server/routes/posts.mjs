import express from "express";
import db from "../db/conn.mjs";
import { ObjectId, GridFSBucket } from "mongodb";
import multer from "multer";
import { Readable } from "stream";

const router = express.Router();

// Setup multer for parsing multipart/form-data (file upload)
const storage = multer.memoryStorage();
const upload = multer({ storage });

// Get a list of 50 posts
router.get("/", async (req, res) => {
  let collection = await db.collection("data");
  let results = await collection.find()
    .limit(50)
    .toArray();

  res.status(200).send(results);
});

// Get a list of posts corresponding to a json array of identifiers
router.post("/receiveInputedData", async (req, res) => {
  try {
    const inputArray = req.body;

    if (!Array.isArray(inputArray)) {
      return res.status(400).json({ error: "Expected an array of objects." });
    }

    // Extract 'code' values and convert to ObjectId
    const ids = inputArray
      .filter(item => item && item.code)
      .map(item => {
        try {
          return new ObjectId(item.code);
        } catch (err) {
          return null; // Skip invalid ObjectId strings
        }
      })
      .filter(id => id !== null); // Remove any null entries

    const collection = await db.collection("data");
    const results = await collection.find({ _id: { $in: ids } }).toArray();

    return res.status(200).json(results);
  } catch (error) {
    console.error("Error retrieving data from database:", error);
    return res.status(500).json({ error: "Internal server error." });
  }
});

// Get a single post
router.get("/:id", async (req, res) => {
  let collection = await db.collection("data");
  let query = { _id: ObjectId(req.params.id) };
  let result = await collection.findOne(query);

  if (!result) return res.status(404).send("Not found");
  else return res.status(200).send(result);
});

// Add a new document to the collection
router.post("/receive-form", async (req, res) => {
  let collection = await db.collection("data");
  let newDocument = req.body;
  newDocument.date = new Date();
  let result = await collection.insertOne(newDocument);
  res.status(200).json({
    acknowledged: result.acknowledged,
    insertedId: result.insertedId
  });
});

// Update the post with a new comment
router.delete("/:id", async (req, res) => {
  try {
    const id = req.params.id;

    if (!ObjectId.isValid(id)) {
      return res.status(400).json({ error: "Invalid ID format" });
    }

    const docId = new ObjectId(id);
    const collection = db.collection("data");

    // Find the main document
    const document = await collection.findOne({ _id: docId });
    if (!document) {
      return res.status(404).json({ error: "Document not found" });
    }

    // If there's a processed file, delete it from GridFS
    if (document.processedFileId) {
      const bucket = new GridFSBucket(db, { bucketName: "processedFiles" });

      try {
        await bucket.delete(new ObjectId(document.processedFileId));
      } catch (err) {
        console.warn("Warning: Failed to delete file from GridFS:", err.message);
        // Continue to delete the document anyway
      }
    }

    // Delete the main document
    const result = await collection.deleteOne({ _id: docId });

    return res.status(200).json({ deletedCount: result.deletedCount });
  } catch (err) {
    console.error("Error deleting document:", err);
    return res.status(500).json({ error: "Internal server error" });
  }
});

// Delete an entry
router.delete("/:id", async (req, res) => {
  const query = { _id: ObjectId(req.params.id) };

  const collection = await db.collection("data");
  let result = await collection.deleteOne(query);

  res.status(200).send(result);
});

/**
 * Upload processed file, save to GridFS, and update DB document with file info
 * Expects multipart/form-data with fields:
 * - id (document ObjectId)
 * - file (file data)
 */
router.post("/uploadProcessedFile", upload.single("file"), async (req, res) => {
  try {
    const { id } = req.body;
    if (!id || !ObjectId.isValid(id)) {
      return res.status(400).json({ error: "Invalid or missing 'id' field" });
    }
    if (!req.file) {
      return res.status(400).json({ error: "Missing file upload" });
    }

    const fileBuffer = req.file.buffer;
    const filename = req.file.originalname;

    // Create GridFS bucket
    const bucket = new GridFSBucket(db, { bucketName: "processedFiles" });

    // Upload stream from the buffer
    const readableStream = Readable.from(fileBuffer);

    // Upload to GridFS
    let uploadStream = bucket.openUploadStream(filename);

    readableStream.pipe(uploadStream)
      .on("error", (error) => {
        console.error("Error uploading file to GridFS:", error);
        res.status(500).json({ error: "Error uploading file" });
      })
      .on("finish", async () => {
        // After file is uploaded, update the document with the GridFS file ID
        const collection = db.collection("data");
        const updateResult = await collection.updateOne(
          { _id: new ObjectId(id) },
          { $set: { processedFileId: uploadStream.id, processedFileName: filename, processedAt: new Date() } }
        );

        if (updateResult.matchedCount === 0) {
          return res.status(404).json({ error: "Document not found to update" });
        }

        res.status(200).json({ message: "File uploaded and document updated", fileId: uploadStream.id });
      });

  } catch (error) {
    console.error("Error in /uploadProcessedFile:", error);
    res.status(500).json({ error: "Internal server error" });
  }
});

router.get("/download/final/:docId", async (req, res) => {
  try {
    const docId = new ObjectId(req.params.docId);

    // Step 1: Find the main document
    const collection = db.collection("data");
    const document = await collection.findOne({ _id: docId });

    if (!document) {
      return res.status(404).send("Document not found");
    }

    // Step 2: Extract the GridFS file id from the document
    const fileId = document.processedFileId;
    if (!fileId) {
      return res.status(404).send("No associated file found for this document");
    }

    // Step 3: Use GridFSBucket to find and stream the file
    const bucket = new GridFSBucket(db, { bucketName: "processedFiles" });

    // Find file info (optional but good for headers)
    const files = await bucket.find({ _id: fileId }).toArray();
    if (!files || files.length === 0) {
      return res.status(404).send("File not found in GridFS");
    }
    const file = files[0];

    res.set({
      'Content-Type': file.contentType || 'application/octet-stream',
      'Content-Disposition': `attachment; filename="${file.filename}"`,
    });

    // Stream the file content
    const downloadStream = bucket.openDownloadStream(fileId);
    downloadStream.pipe(res);

    downloadStream.on("error", (error) => {
      console.error("GridFS download error:", error);
      res.status(500).send("Error streaming the file");
    });

  } catch (error) {
    console.error("Error in download route:", error);
    res.status(400).send("Invalid document ID");
  }
});

router.patch("/updateIv/:id", async (req, res) => {
  try {
    const { id } = req.params;
    const { requestIv } = req.body;

    console.log("PATCH /updateIv called with id:", id, "and requestIv:", requestIv);

    if (!requestIv) {
      return res.status(400).json({ error: "'requestIv' field is required" });
    }

    if (!ObjectId.isValid(id)) {
      return res.status(400).json({ error: "Invalid 'id' format" });
    }

    const collection = db.collection("data");

    const updateResult = await collection.updateOne(
      { _id: new ObjectId(id) },
      { $set: { requestIv } }
    );

    if (updateResult.matchedCount === 0) {
      return res.status(404).json({ error: "Document not found" });
    }

    return res.status(200).json({ message: "requestIv updated successfully" });
  } catch (error) {
    console.error("Full error stack:", error.stack);
    return res.status(500).json({ error: error.message });
  }
});

router.post("/check-password/:id", async (req, res) => {
  try {
    const { id } = req.params;
    const { password } = req.body;

    if (!password) {
      return res.status(400).json({ error: "Password is required in the request body." });
    }

    if (!ObjectId.isValid(id)) {
      return res.status(400).json({ error: "Invalid document ID format." });
    }

    const collection = db.collection("data");
    const document = await collection.findOne({ _id: new ObjectId(id) });

    if (!document) {
      return res.status(404).json({ error: "Document not found." });
    }

    if (document.requestPassword === password) {
      return res.status(200).json({ success: true, message: "Password is correct." });
    } else {
      return res.status(401).json({ success: false, message: "Incorrect password." });
    }

  } catch (error) {
    console.error("Error checking password:", error);
    return res.status(500).json({ error: "Internal server error." });
  }
});

export default router;
