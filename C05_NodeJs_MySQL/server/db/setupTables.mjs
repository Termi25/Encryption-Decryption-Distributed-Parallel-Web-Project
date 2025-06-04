import pool from "./conn.mjs";

async function createTables() {
  const connection = await pool.getConnection();
  try {
    await connection.beginTransaction();

    // Create main data table
    await connection.query(`
      CREATE TABLE IF NOT EXISTS data (
        id INT AUTO_INCREMENT PRIMARY KEY,
        requestIv VARCHAR(255),
        mode VARCHAR(50),
        aesLength VARCHAR(10),
        requestPassword VARCHAR(255),
        fileName VARCHAR(255),
        operation VARCHAR(50),
        date DATETIME DEFAULT CURRENT_TIMESTAMP,
        processedAt DATETIME NULL
      )
    `);

    // Create processed files table, linked 1:1 to data by data_id
    await connection.query(`
      CREATE TABLE IF NOT EXISTS processed_files (
        id INT AUTO_INCREMENT PRIMARY KEY,
        data_id INT NOT NULL UNIQUE,
        fileName VARCHAR(255),
        fileData LONGBLOB,
        contentType VARCHAR(255),
        FOREIGN KEY (data_id) REFERENCES data(id) ON DELETE CASCADE
      )
    `);

    await connection.commit();
    console.log("Tables created or verified successfully");
  } catch (error) {
    await connection.rollback();
    console.error("Failed to create tables:", error);
    throw error;
  } finally {
    connection.release();
  }
}

export default createTables;