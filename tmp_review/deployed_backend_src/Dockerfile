# Use Node.js LTS (Iron) on Alpine for a small footprint
FROM node:20-alpine

# Set working directory
WORKDIR /usr/src/app

# Copy package files
COPY package*.json ./

# Install production dependencies
RUN npm install --omit=dev

# Copy application source
COPY . .

# Cloud Run expects the app to listen on the PORT environment variable
ENV PORT 8080
EXPOSE 8080

# Start the application
CMD [ "node", "index.js" ]
